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

/** 单实例文件索引。MySQL 为事实来源，向量按拾物记录 ID 覆盖更新。 */
@Service
@Slf4j
public class LostPropertyIndexService implements ApplicationRunner {
    private static final int FORMAT_VERSION = 1;
    private final LostPropertyMapper mapper;
    private final EmbeddingModel model;
    private final Path file;
    private final String modelVersion;
    private final ObjectMapper json = new ObjectMapper();
    private volatile State state = buildState(Map.of());
    private FileChannel lockChannel;
    private FileLock fileLock;
    private boolean needsCheckpoint;

    public record Clue(Long id, String lostName, String lostType) { }
    public record Entry(Clue clue, List<Float> vector) { }
    public record Checkpoint(int formatVersion, String modelVersion, List<Entry> entries) { }
    private record State(Map<Long, Entry> entries, InMemoryEmbeddingStore<TextSegment> store) { }

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

    /** 启动导入旧登记，补齐停机期间变化，清除已删除的记录。 */
    @Override
    public void run(ApplicationArguments args) throws IOException {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${lost-found.index.reconcile-delay-ms:60000}",
            initialDelayString = "${lost-found.index.reconcile-delay-ms:60000}")
    public void retrySynchronization() {
        try {
            reconcile();
        } catch (Exception ex) {
            log.error("拾物索引补偿同步失败，将在下一轮重试", ex);
        }
    }

    public synchronized int reconcile() throws IOException {
        Map<Long, Entry> next = new LinkedHashMap<>();
        int changed = 0;
        for (LostPropertyEntity entity : mapper.findAllForIndex()) {
            Clue clue = clueOf(entity);
            if (!searchable(clue)) continue;
            Entry old = state.entries().get(clue.id());
            if (old != null && old.clue().equals(clue)) {
                next.put(clue.id(), old);
            } else {
                next.put(clue.id(), embed(clue));
                changed++;
            }
        }
        if (needsCheckpoint || !next.equals(state.entries())) commit(next);
        log.info("拾物索引核对完成，记录数={}，重新计算向量数={}", next.size(), changed);
        return next.size();
    }

    /** 在锁内重读数据库，避免并发请求用旧实体覆盖新索引。 */
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

    /** 事务提交后同步；失败由定时核对补偿，避免用户重复提交已保存的数据。 */
    public boolean syncAfterCommit(Long id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    trySync(id);
                }
            });
            return false; // 已排队，尚不能保证查询可见。
        }
        return trySync(id);
    }

    private boolean trySync(Long id) {
        try {
            syncRecord(id);
            return true;
        } catch (Exception ex) {
            log.error("拾物登记已保存，但索引同步失败，recordId={}，等待补偿重试", id, ex);
            return false;
        }
    }

    /** 全平台匹配；记录修改权限仍由业务保存流程检查。 */
    public List<String> search(String description) {
        if (description == null || description.isBlank()) return List.of();
        State snapshot = state;
        if (snapshot.entries().isEmpty()) return List.of();
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(model.embed(description.trim()).content())
                .maxResults(5).minScore(0.5).build();
        List<String> result = new ArrayList<>();
        for (var match : snapshot.store().search(request).matches()) {
            long id = Long.parseLong(match.embeddingId());
            Entry indexed = snapshot.entries().get(id);
            LostPropertyEntity current = mapper.findByIdForIndex(id);
            // 同步失败或外部删除时，不返回过期线索。
            if (current != null && Objects.equals(indexed.clue(), clueOf(current))) {
                result.add(JsonUtils.toJson(indexed.clue()));
            }
        }
        return result;
    }

    private Entry embed(Clue clue) {
        return new Entry(clue, List.copyOf(model.embed(textOf(clue)).content().vectorAsList()));
    }

    /** 先原子落盘，再发布新的查询快照。 */
    private void commit(Map<Long, Entry> entries) throws IOException {
        if (fileLock == null || !fileLock.isValid()) throw new IOException("拾物索引未初始化或已关闭");
        State next = buildState(entries);
        Path temporary = Files.createTempFile(file.getParent(), "lost-property-index-", ".tmp");
        try {
            json.writeValue(temporary.toFile(), new Checkpoint(FORMAT_VERSION, modelVersion,
                    new ArrayList<>(entries.values())));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            state = next;
            needsCheckpoint = false;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static State buildState(Map<Long, Entry> entries) {
        var store = new InMemoryEmbeddingStore<TextSegment>();
        entries.forEach((id, entry) -> store.addAll(List.of(id.toString()),
                List.of(Embedding.from(entry.vector())), List.of(TextSegment.from(textOf(entry.clue())))));
        return new State(Map.copyOf(entries), store);
    }

    private static Clue clueOf(LostPropertyEntity entity) {
        return new Clue(entity.getId(), clean(entity.getLostName()), clean(entity.getLostType()));
    }

    private static boolean searchable(Clue clue) {
        return !clue.lostName().isBlank() || !clue.lostType().isBlank();
    }

    private static String textOf(Clue clue) {
        return "物品名称：" + clue.lostName() + "\n物品特征：" + clue.lostType();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

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
