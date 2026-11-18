package com.lostsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lostsystem.entity.LostPropertyEntity;
import com.lostsystem.mapper.LostPropertyMapper;
import com.lostsystem.utils.JsonUtils;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 拾物向量索引服务
 * 单实例文件型向量索引。MySQL 作为权威数据源，向量索引以拾物记录ID为主键进行覆盖更新；
 * 索引持久化到本地JSON文件，启动加载、定时全量核对、新增/修改登记支持事务后增量同步，
 * 向量检索时二次校验MySQL，防止返回已经删除或变更的过期数据。
 */
@Service
@Slf4j
public class LostPropertyIndexService implements ApplicationRunner {
    /** 索引文件格式版本，用于兼容升级，版本不一致会全量重建索引 */
    private static final int FORMAT_VERSION = 1;
    private final LostPropertyMapper mapper;
    private final EmbeddingModel model;
    /** 索引文件绝对路径 */
    private final Path file;
    /** 向量模型版本号；模型更换时旧索引失效，启动自动重建 */
    private final String modelVersion;
    private final ObjectMapper json = new ObjectMapper();

    /** 当前内存索引快照，volatile保证检索线程可见最新状态 */
    private volatile State state = buildState(Map.of());
    /** 文件锁通道与锁对象，防止多进程实例同时读写同一个索引文件 */
    private FileChannel lockChannel;
    private FileLock fileLock;
    /** 是否需要执行一次落盘checkpoint标记 */
    private boolean needsCheckpoint;

    /**
     * 索引线索记录：一条拾物记录用于向量化的核心字段
     * @param id 拾物记录主键
     * @param lostName 物品名称
     * @param lostType 物品类型/特征
     */
    public record Clue(
            Long id,
            String lostName,
            String lostType
    ) { }

    /**
     * 索引条目：线索 + 对应向量
     * @param clue 拾物线索文本信息
     * @param vector embedding向量数组
     */
    public record Entry(
            Clue clue,
            List<Float> vector
    ) { }

    /**
     * 索引持久化快照结构，写入JSON文件
     * @param formatVersion 文件格式版本
     * @param modelVersion 生成这批向量使用的embedding模型版本
     * @param entries 全部索引条目列表
     */
    public record Checkpoint(
            int formatVersion,
            String modelVersion,
            List<Entry> entries
    ) { }

    /**
     * 内存索引状态快照
     * @param entries 拾物ID -> 索引Entry映射表
     * @param store 内存向量库，提供相似度检索能力
     */
    private record State(
            Map<Long, Entry> entries,
            InMemoryEmbeddingStore<TextSegment> store
    ) { }

    public LostPropertyIndexService(
            LostPropertyMapper mapper,
            EmbeddingModel model,
            @Value("${lost-found.index.path:./data/lost-property-index.json}") String path,
            @Value("${lost-found.index.model-version:bge-small-zh-v15-q-v1}") String modelVersion) {
        this.mapper = mapper;
        this.model = model;
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.modelVersion = modelVersion;
    }

    /**
     * Bean初始化方法：加载本地索引文件、加文件锁、校验版本与数据合法性
     * 加synchronized防止并发初始化；文件锁保证同一索引文件只能被一个进程占用
     */
    @PostConstruct
    public synchronized void initialize() throws IOException {
        if (fileLock != null) return;
        Files.createDirectories(file.getParent());
        try {
            lockChannel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = lockChannel.tryLock();
            if (fileLock == null) throw new IOException("索引目录已被其他应用实例使用：" + file);
            needsCheckpoint = true;
            if (Files.exists(file)) {
                Checkpoint checkpoint = json.readValue(file.toFile(), Checkpoint.class);
                if (checkpoint.formatVersion() != FORMAT_VERSION) {
                    throw new IOException("不支持的拾物索引文件版本：" + file);
                }
                if (modelVersion.equals(checkpoint.modelVersion())) {
                    Map<Long, Entry> entries = new LinkedHashMap<>();
                    Integer dimension = null;
                    for (Entry entry : checkpoint.entries()) {
                        // 校验索引条目合法性，过滤损坏、非法向量
                        if (entry.clue() == null || entry.clue().id() == null || entry.clue().id() <= 0
                                || entry.vector() == null || entry.vector().isEmpty()
                                || entry.vector().stream().anyMatch(v -> v == null || !Float.isFinite(v))
                                || (dimension != null && dimension != entry.vector().size())
                                || entries.put(entry.clue().id(), entry) != null) {
                            throw new IOException("拾物索引文件数据损坏：" + file);
                        }
                        dimension = entry.vector().size();
                    }
                    state = buildState(entries);
                    needsCheckpoint = false;
                } else {
                    log.info("向量模型版本变化，启动时将重新构建拾物索引");
                }
            }
        } catch (IOException | RuntimeException ex) {
            close();
            throw ex;
        }
    }

    /**
     * Spring容器启动完成后执行一次全量核对同步
     * 导入数据库全部有效拾物记录，补齐停机期间的数据变更、清理已经删除的记录
     */
    @Override
    public void run(ApplicationArguments args) throws IOException {
        reconcile();
    }

    /**
     * 定时补偿同步任务
     * 固定间隔执行全量核对，作为增量同步失败兜底，自动修复索引和MySQL不一致
     */
    @Scheduled(fixedDelayString = "${lost-found.index.reconcile-delay-ms:60000}",
            initialDelayString = "${lost-found.index.reconcile-delay-ms:60000}")
    public void retrySynchronization() {
        try {
            reconcile();
        } catch (Exception ex) {
            log.error("拾物索引补偿同步失败，将在下一轮重试", ex);
        }
    }

    /**
     * 全量核对同步（补偿主逻辑）
     * 以 MySQL 数据库作为权威真值来源，遍历所有需要建立索引的拾物记录，构建新一版索引集合；
     * 和当前内存里的索引逐条对比：线索内容没有改动的直接复用已经生成好的向量，不再重复调用 Embedding 模型，节约算力；
     * 只有新增记录、物品名称/特征发生修改的记录，才重新向量化；
     * 不在数据库结果集里的旧索引条目（已经删除、不再满足检索条件）会自动丢弃。
     * @return 本轮核对完成后，内存中有效的索引条目总数量
     */
    public synchronized int reconcile() throws IOException {
        // 构建新一版索引 Map：key=拾物记录ID，value=索引条目Entry
        // LinkedHashMap 保证顺序稳定，方便后续 equals 对比、落盘序列化
        Map<Long, Entry> next = new LinkedHashMap<>();
        // 统计本轮需要重新生成向量的记录数量
        int changed = 0;

        // 查询 MySQL 所有需要纳入向量索引的拾物记录
        for (LostPropertyEntity entity : mapper.findAllForIndex()) {
            // 从数据库实体提取用于向量化的线索（物品名称+物品特征）
            Clue clue = clueOf(entity);
            // 过滤不具备检索价值的线索（名称、特征都为空，不需要建向量）
            if (!searchable(clue)) continue;

            // 从当前正在使用的内存索引中，取出该ID对应的旧索引条目
            Entry old = state.entries().get(clue.id());
            // 分支1：旧条目存在，并且线索文本完全一致 → 内容没有改动，直接复用旧向量
            if (old != null && old.clue().equals(clue)) {
                next.put(clue.id(), old);
            } else {
                // 分支2：新增记录 / 线索文本修改 / 旧索引不存在 → 重新调用Embedding生成向量
                next.put(clue.id(), embed(clue));
                changed++;
            }
        }

        // 满足任一条件，就把新版索引原子持久化落地并切换内存快照：
        // ① 标记了需要强制落盘（needsCheckpoint=true）；
        // ② 新旧索引集合不相等，代表索引确实发生变化
        if (needsCheckpoint || !next.equals(state.entries())) commit(next);

        log.info("拾物索引核对完成，记录数={}，重新计算向量数={}", next.size(), changed);
        return next.size();
    }


    /**
     * 单条拾物记录增量同步（锁内重读数据库）
     * 用于新增/修改/删除单条记录后更新索引；记录不存在或者不可检索时直接从索引移除
     * @param id 拾物记录主键
     */
    public synchronized void syncRecord(Long id) throws IOException {
        if (id == null || id <= 0) throw new IllegalArgumentException("拾物记录ID不正确");
        LostPropertyEntity entity = mapper.findByIdForIndex(id);
        Map<Long, Entry> next = new LinkedHashMap<>(state.entries());
        if (entity == null || !searchable(clueOf(entity))) {
            next.remove(id);
        } else {
            Clue clue = clueOf(entity);
            Entry old = next.get(id);
            if (old == null || !old.clue().equals(clue)) next.put(id, embed(clue));
        }
        if (needsCheckpoint || !next.equals(state.entries())) commit(next);
    }

    /**
     * 事务提交后同步索引入口
     * 如果当前处在事务中，注册事务回调，等事务提交成功后再同步索引；
     * 不在事务里则直接尝试同步；同步失败交给定时reconcile补偿，避免阻塞主业务流程
     * @param id 拾物记录id
     * @return true：同步立即成功；false：已注册事务回调排队，不一定立即可查
     */
    public boolean syncAfterCommit(Long id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    trySync(id);
                }
            });
            return false;
        }
        return trySync(id);
    }

    /**
     * 尝试单条索引同步，捕获异常，失败返回false留给定时任务补偿
     */
    private boolean trySync(Long id) {
        try {
            syncRecord(id);
            return true;
        } catch (Exception ex) {
            log.error("拾物登记已保存，但索引同步失败，recordId={}，等待补偿重试", id, ex);
            return false;
        }
    }

    /**
     * 向量相似度检索
     * 先在内存向量库召回相似度靠前的候选结果，再二次查询MySQL做一致性校验；
     * 过滤索引中存在但数据库已删除、或者内容已经变更的过期脏数据，保证返回结果和数据库真值一致。
     * @param description 用户输入的物品描述文本
     * @return 通过一致性校验的线索JSON字符串列表
     */
    public List<String> search(String description) {
        // 入参为空直接返回空列表，避免无效向量化调用
        if (description == null || description.isBlank()) return List.of();
        // 读取当前索引快照，一次性拿到state引用，防止遍历过程中state被commit替换（快照读）
        State snapshot = state;
        // 当前索引为空，直接返回
        if (snapshot.entries().isEmpty())
            return List.of();

        // 构造向量检索请求：对用户描述生成query向量，最多召回5条，相似度阈值0.5
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(model.embed(description.trim()).content())
                .maxResults(5).minScore(0.5).build();

        List<String> result = new ArrayList<>();
        // 遍历向量库召回得到的匹配候选
        for (var match : snapshot.store().search(request).matches()) {
            // 取出向量库存储时绑定的embeddingId，也就是拾物记录ID字符串
            long id = Long.parseLong(match.embeddingId());
            // 从快照的条目映射取出索引里缓存的线索
            Entry indexed = snapshot.entries().get(id);
            // 回查MySQL，拿到这条记录在数据库里的最新真值
            LostPropertyEntity current = mapper.findByIdForIndex(id);
            // 一致性校验：数据库记录存在，并且当前库中的线索和索引里缓存的线索完全一致，才认为有效
            // 防止索引还没来得及同步、记录已经删除/修改，导致返回过期数据
            if (current != null && Objects.equals(indexed.clue(), clueOf(current))) {
                result.add(JsonUtils.toJson(indexed.clue()));
            }
        }
        return result;
    }


    /**
     * 对一条线索生成向量，封装成索引Entry
     */
    private Entry embed(Clue clue) {
        return new Entry(clue, List.copyOf(model.embed(textOf(clue)).content().vectorAsList()));
    }

    /**
     * 原子落盘快照：先写入临时文件、刷盘持久化，再原子rename覆盖原文件
     * 采用“先写临时文件、刷盘、原子替换”的模式，避免直接覆盖主索引文件；
     * 防止进程崩溃、断电时产生半写、损坏的索引文件；
     * 文件持久化成功之后，才替换 volatile 修饰的 state 快照，新检索请求立刻读取新版索引，保证对外可见性。
     * @param entries 本轮需要持久化的全量索引条目映射
     */
    private void commit(Map<Long, Entry> entries) throws IOException {
        // 校验文件锁状态，索引未正常初始化/锁失效时禁止写入
        if (fileLock == null || !fileLock.isValid()) throw new IOException("拾物索引未初始化或已关闭");

        // 基于最新条目集合构建全新的内存索引快照（包含新的InMemoryEmbeddingStore）
        State next = buildState(entries);

        // 在索引文件同目录创建临时文件，作为写入缓冲区
        Path temporary = Files.createTempFile(file.getParent(), "lost-property-index-", ".tmp");
        try {
            // 将Checkpoint快照对象序列化写入临时文件
            json.writeValue(temporary.toFile(), new Checkpoint(FORMAT_VERSION, modelVersion,
                    new ArrayList<>(entries.values())));

            // force(true): 强制把操作系统页缓存的数据刷落到磁盘硬件，确保数据真正持久化，不只停留在内存缓存
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            // ATOMIC_MOVE：原子移动，操作系统保证这个操作要么全部成功，要么完全不改动目标文件；
            // REPLACE_EXISTING：如果目标主文件已存在，则覆盖
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            // 落盘成功，替换volatile state，检索线程可以读到新索引快照
            state = next;
            // 清除强制落盘标记
            needsCheckpoint = false;
        } finally {
            // 无论成功失败，尝试删除临时文件，避免残留垃圾文件
            Files.deleteIfExists(temporary);
        }
    }


    /**
     * 根据拾物ID到索引条目的映射表，组装生成一份全新的内存索引快照State
     * 新建独立的InMemoryEmbeddingStore向量存储，把所有Entry的向量、文本、标识ID灌入向量库；
     * 同时把原entries做不可变拷贝存入State，保证State内部集合只读，避免外部修改破坏快照一致性。
     * @param entries 拾物记录ID -> 索引Entry的映射
     * @return 封装好的不可变索引State快照，包含条目映射与内存向量库
     */
    private static State buildState(Map<Long, Entry> entries) {
        // 新建内存向量库实例，用于后续相似度检索
        var store = new InMemoryEmbeddingStore<TextSegment>();
        // 遍历全部索引条目，批量写入向量库：
        // 第一组参数：向量标识id（拾物记录id转字符串，检索后用来反向匹配Entry）
        // 第二组参数：向量数据
        // 第三组参数：原始文本片段（用于向量库内部关联文本信息）
        entries.forEach((id, entry) -> store.addAll(List.of(id.toString()),
                List.of(Embedding.from(entry.vector())), List.of(TextSegment.from(textOf(entry.clue())))));
        // Map.copyOf生成不可变Map，防止后续外部修改集合；和向量库一起打包为State返回
        return new State(Map.copyOf(entries), store);
    }


    /**
     * 从实体提取可索引线索对象
     */
    private static Clue clueOf(LostPropertyEntity entity) {
        return new Clue(
                entity.getId(),
                clean(entity.getLostName()),
                clean(entity.getLostType()));
    }

    /**
     * 判断线索是否具备检索价值：名称或类型至少有一个不为空
     */
    private static boolean searchable(Clue clue) {
        return !clue.lostName().isBlank() || !clue.lostType().isBlank();
    }

    /**
     * 拼接送入Embedding模型的文本
     */
    private static String textOf(Clue clue) {
        return "物品名称：" + clue.lostName() + "\n物品特征：" + clue.lostType();
    }

    /**
     * 文本清洗，null转为空字符串并去除首尾空白
     */
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Bean销毁时释放文件锁和通道
     */
    @PreDestroy
    public synchronized void close() throws IOException {
        try {
            if (fileLock != null) fileLock.release();
        } finally {
            fileLock = null;
            if (lockChannel != null) lockChannel.close();
        }
    }
}
