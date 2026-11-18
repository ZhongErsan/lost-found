package com.lostsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lostsystem.aioutput.IntentionOutput;
import com.lostsystem.aioutput.LostPropertyOutput;
import com.lostsystem.aioutput.LostRegisterOutput;
import com.lostsystem.aioutput.RegistrationAction;
import com.lostsystem.aiservice.AiIntentionAssistant;
import com.lostsystem.aiservice.LostRegisterAssistant;
import com.lostsystem.aiservice.LostPropertyAssistant;
import com.lostsystem.aop.ChatFlow;
import com.lostsystem.auth.RegistrationContact;
import com.lostsystem.chatmemory.ConversationMemory;
import com.lostsystem.dto.ChatStreamEvent;
import com.lostsystem.entity.LostPropertyEntity;
import com.lostsystem.entity.LostRegisterEntity;
import com.lostsystem.entity.ChatHistoryEntity;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.ChatHistoryMapper;
import com.lostsystem.mapper.LostPropertyMapper;
import com.lostsystem.mapper.LostRegisterMapper;
import com.lostsystem.service.AiChatService;
import com.lostsystem.service.ChatConversationService;
import com.lostsystem.service.LostPropertyIndexService;
import com.lostsystem.utils.JsonUtils;
import com.lostsystem.vo.ChatHistoryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * AI聊天业务实现类
 * 核心流程：接收用户消息 -> AI意图识别 -> 根据意图分发至不同业务分支
 * 意图定义：
 * 1：失物登记（lost）
 * 2：拾物登记（found）
 * 3：失物检索查询
 * 默认：普通问答，直接返回意图识别产出文本
 * 流式返回 Flux<ChatStreamEvent>，支持 NDJSON 流式接口；同时封装历史记录、清空会话、向量索引能力
 */
@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {

    /**
     * AI意图识别助手：判断用户当前消息属于哪一类业务意图
     */
    @Autowired
    private AiIntentionAssistant aiIntentionAssistant;
    /**
     * 失物登记助手：对话式收集丢失物品信息，生成登记结构体
     */
    @Autowired
    private LostRegisterAssistant lostRegisterAssistant;
    /**
     * 丢失登记Mapper：操作失物登记表
     */
    @Autowired
    private LostRegisterMapper lostRegisterMapper;
    /**
     * 聊天历史Mapper：存储每一轮问答记录
     */
    @Autowired
    private ChatHistoryMapper chatHistoryMapper;
    /**
     * 会话记忆组件：维护LLM对话上下文记忆
     */
    @Autowired
    private ConversationMemory conversationMemory;
    /**
     * 拾物登记助手：对话式收集拾物信息，生成拾物登记结构体
     */
    @Autowired
    private LostPropertyAssistant lostPropertyAssistant;
    /**
     * 拾物向量索引服务：负责知识库向量构建、增量同步、相似度检索
     */
    @Autowired
    private LostPropertyIndexService lostPropertyIndexService;
    /**
     * 拾物登记Mapper：操作拾物登记表
     */
    @Autowired
    private LostPropertyMapper lostPropertyMapper;
    /**
     * 会话服务：校验会话归属、根据sessionId反向查询用户ID与联系方式
     */
    @Autowired
    private ChatConversationService chatConversationService;

    /**
     * 前端排序字段 -> 数据库真实列名映射，防止SQL注入，只开放允许的排序字段
     */
    private static final Map<String, String> HISTORY_SORT_COLUMNS = Map.of(
            "id", "id",
            "createdDate", "created_date",
            "updatedDate", "updated_date");

    /**
     * 分页查询指定会话的聊天历史记录
     * 自行拼接 LIMIT OFFSET，不依赖MyBatis分页插件；增加id作为兜底排序键，保证分页稳定
     *
     * @param sessionId 会话唯一标识
     * @param page      分页参数：页码、页大小、排序规则
     * @return 分页包装对象，包含记录列表、总数、分页信息
     */
    @Override
    public Page<ChatHistoryVO> queryChatHistory(String sessionId, Pageable page) {
        requireSessionId(sessionId);
        // 分页参数合法性校验，禁止不分页、单页条数过大
        if (page == null || page.isUnpaged() || page.getPageSize() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每页记录数必须在 1 到 100 之间");
        }

        QueryWrapper<ChatHistoryEntity> query = new QueryWrapper<>();
        query.eq("session_id", sessionId);

        // 排序处理：无排序时默认按id倒序
        Sort sort = page.getSort().isSorted() ? page.getSort() : Sort.by(Sort.Direction.DESC, "id");
        for (Sort.Order order : sort) {
            String column = HISTORY_SORT_COLUMNS.get(order.getProperty());
            if (column == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持按 id、createdDate、updatedDate 排序");
            }
            query.orderBy(true, order.isAscending(), column);
        }

        // 兜底：增加id作为稳定排序字段，避免时间戳相同情况下分页数据错乱
        if (sort.getOrderFor("id") == null) {
            query.orderByDesc("id");
        }

        // 查询该会话全部记录总数
        long total = chatHistoryMapper.selectCount(new QueryWrapper<ChatHistoryEntity>().eq("session_id", sessionId));
        // offset超出总记录数，直接返回空页
        if (page.getOffset() >= total) {
            return new PageImpl<>(List.of(), page, total);
        }

        // 手动拼接 LIMIT OFFSET，由Pageable参数控制，无用户原生SQL输入，安全
        query.last("LIMIT " + page.getPageSize() + " OFFSET " + page.getOffset());
        List<ChatHistoryVO> records = chatHistoryMapper.selectList(query).stream()
                .map(ChatHistoryVO::from).toList();
        return new PageImpl<>(records, page, total);
    }

    /**
     * 清空当前会话聊天历史
     * 事务：同时删除数据库聊天记录 + 清空内存/缓存中的对话记忆
     *
     * @param sessionId 会话标识
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearChatHistory(String sessionId) {
        requireSessionId(sessionId);
        // 删除数据库历史
        chatHistoryMapper.delete(new QueryWrapper<ChatHistoryEntity>().eq("session_id", sessionId));
        // 清空会话记忆，避免后续对话读到旧上下文
        conversationMemory.clear(sessionId);
    }

    /**
     * 重建/同步拾物向量索引（管理员接口）
     * reconcile：比对数据库与向量库差异，增量更新索引
     *
     * @return 同步成功提示与索引数量
     */
    @Override
    public String embeddingIndex() {
        try {
            return "success，索引记录数=" + lostPropertyIndexService.reconcile();
        } catch (java.io.IOException ex) {
            throw new BusinessException(503, "拾物索引同步失败，请稍后重试", ex);
        }
    }

    /**
     * 向量检索：根据用户文本，召回相似度匹配的拾物知识库文本片段
     *
     * @param message 用户检索query
     * @return 召回文本列表
     */
    @Override
    public List<String> embeddingQuery(String message) {
        return lostPropertyIndexService.search(message);
    }

    /**
     * sessionId基础校验：非空非空白
     *
     * @param sessionId 会话标识
     */
    private void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId（当前会话标识）不能为空");
        }
    }

    /**
     * AI流式对话入口
     * 注解 @ChatFlow：自定义切面标记，用于记录对话流程、埋点或日志
     * 流程：意图识别 -> 根据意图分发到对应业务流
     * 意图识别是阻塞IO操作，调度到 boundedElastic 线程池，不占用Netty事件循环线程
     *
     * @param sessionId 会话ID
     * @param message   用户输入消息
     * @return ChatStreamEvent 流式事件流，前端逐行消费
     */
    @Override
    @ChatFlow
    public Flux<ChatStreamEvent> chatStream(String sessionId, String message) {
        requireSessionId(sessionId);

        if (message == null || message.isBlank()) {
            return Flux.error(new IllegalArgumentException("消息不能为空"));
        }

        return Mono.fromCallable(() -> {
                    // 调用意图识别助手，识别当前用户请求属于哪一类业务
                    IntentionOutput result = aiIntentionAssistant.intention(sessionId, message);
                    if (result == null) {
                        throw new IllegalStateException("意图识别助手返回内容为空");
                    }
                    return result;
                })
                // 根据识别出的意图，路由到对应业务分支
                .flatMapMany(result -> streamByIntention(sessionId, message, result))
                // 意图识别属于阻塞IO，放到弹性线程池执行，防止阻塞reactor主线程
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 根据意图编号路由业务分支
     * 意图说明：
     * 1：失物登记（lost），同步组装结果，一次性返回事件列表
     * 2：拾物登记（found），同步组装结果，一次性返回事件列表
     * 3：失物检索，返回模型原生Flux流式逐字输出
     * null/其他：直接返回意图识别输出文本
     *
     * @param sessionId       会话ID
     * @param message         用户消息
     * @param intentionOutput 意图识别结果
     * @return 流式事件
     */
    private Flux<ChatStreamEvent> streamByIntention(
            String sessionId,
            String message,
            IntentionOutput intentionOutput
    ) {
        String output = intentionOutput.getOutput();
        Integer intention = intentionOutput.getIntention();

        if (intention == null) {
            return output == null
                    ? Flux.empty()
                    : Flux.just(ChatStreamEvent.text(output));
        }

        switch (intention) {
            case 1:
                // 失物登记分支，阻塞执行业务，完成后一次性返回事件集合
                return Mono.fromCallable(() -> registerLost(sessionId, message))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(Flux::fromIterable);
            case 2:
                // 拾物登记分支
                return Mono.fromCallable(() -> findLostPropertyRegister(sessionId, message))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(Flux::fromIterable);
            case 3:
                // 失物检索，原生模型流式输出，逐块返回
                return queryLostProperty(sessionId, message)
                        .map(ChatStreamEvent::text);
            default:
                // 未知意图，返回意图识别输出文本
                return output == null
                        ? Flux.empty()
                        : Flux.just(ChatStreamEvent.text(output));
        }
    }

    /**
     * 失物检索业务逻辑
     * 前置：查询当前用户自己提交的失物登记列表，序列化为上下文给到LLM
     * 然后调用拾物检索助手流式检索知识库，返回打字机效果
     *
     * @param sessionId 会话ID
     * @param message   用户检索提问
     * @return 模型逐段输出文本流
     */
    private Flux<String> queryLostProperty(String sessionId, String message) {
        return Mono.fromCallable(() -> {
                    // 通过sessionId反查所属用户ID
                    Long userId = chatConversationService.requireOwnerUserId(sessionId);
                    // 查询该用户所有【正在寻找】状态的失物登记
                    List<MyLostRegistration> registrations = lostRegisterMapper
                            .findSearchingByUserIdOrderByIdDesc(userId)
                            .stream()
                            .map(entity -> new MyLostRegistration(
                                    entity.getId(),
                                    entity.getLostName(),
                                    entity.getLostType()
                            ))
                            .toList();

                    log.info(
                            "失物查询读取本人登记，userId={}，条数={}",
                            userId,
                            registrations.size()
                    );
                    // 用户自有失物信息转为JSON，作为上下文传入检索prompt
                    return JsonUtils.toJson(registrations);
                })
                .subscribeOn(Schedulers.boundedElastic())
                // 调用检索助手，开启流式大模型回答
                .flatMapMany(registrationsJson ->
                        lostPropertyAssistant.queryLostProperty(
                                sessionId,
                                message,
                                registrationsJson
                        )
                )
                // 流式回答全部结束时打印日志
                .doOnComplete(() ->
                        log.info(
                                "失物查询流式回复完成，sessionId={}",
                                sessionId
                        )
                );
    }

    /**
     * 拾物登记业务：对话收集信息，完成后落地数据库（新增/修改拾物记录）
     * 完成登记时返回两条事件：文本回复事件 + registrationSaved 标记事件，前端识别后刷新列表
     *
     * @param sessionId 会话ID
     * @param message   用户消息
     * @return 事件列表
     */
    private List<ChatStreamEvent> findLostPropertyRegister(String sessionId, String message) {
        Long userId = chatConversationService.requireOwnerUserId(sessionId);
        // 获取用户联系信息（姓名、手机号）
        RegistrationContact contact = chatConversationService.requireOwnerContact(sessionId);
        // 调用拾物登记助手，对话收集信息，判断是否收集完成
        LostPropertyOutput out = lostPropertyAssistant.registerLostProperty(
                sessionId,
                message,
                contact.realName(),
                maskPhone(contact.phone())
        );
        if (out == null) {
            throw new IllegalStateException("拾物登记助手返回内容为空");
        }
        if (!StringUtils.hasText(out.getOutput())) {
            throw new IllegalStateException("拾物登记助手缺少回复内容");
        }
        // 信息尚未收集齐全，只返回对话文本，不入库
        if (!Boolean.TRUE.equals(out.getCompleted())) {
            return List.of(
                    ChatStreamEvent.text(out.getOutput())
            );
        }

        // 信息收集完成，准备入库，校验操作类型和ID匹配关系
        validateRegistrationAction(out.getAction(), out.getId());
        Long id = out.getId();
        LostPropertyEntity entity;
        int rows;

        if (id == null) {
            // 新增拾物登记流程
            validateNewRegistrationContact(
                    contact,
                    out.getContactConfirmed()
            );

            entity = new LostPropertyEntity();
            entity.setUserId(userId);
            entity.setUsername(contact.realName());
            entity.setPhone(contact.phone());
            entity.setLostName(out.getLostName());
            entity.setLostType(out.getLostType());
            // 新增拾物默认状态：可被匹配 AVAILABLE
            entity.setStatus("AVAILABLE");
            entity.setVersion(0L);
            rows = lostPropertyMapper.insert(entity);
        } else {
            // 修改已有拾物登记流程
            if (id <= 0) {
                throw new BusinessException(400, "拾物登记编号不正确");
            }
            // 强校验归属：只能修改自己的拾物记录
            entity = lostPropertyMapper.selectOne(
                    new QueryWrapper<LostPropertyEntity>()
                            .eq("id", id)
                            .eq("user_id", userId)
            );
            if (entity == null) {
                throw new BusinessException(
                        404,
                        "拾物登记不存在或无权修改"
                );
            }
            // 状态校验：已经交接/归还的拾物不能修改
            if (!"AVAILABLE".equals(entity.getStatus())) {
                throw new BusinessException(
                        409,
                        "当前拾物登记已经进入交接或归还状态，不能继续修改"
                );
            }

            // 优先使用AI新识别到的值，没有新值则保留原有
            String lostName = entity.getLostName();
            String lostType = entity.getLostType();
            if (StringUtils.hasText(out.getLostName())) {
                lostName = out.getLostName();
            }
            if (StringUtils.hasText(out.getLostType())) {
                lostType = out.getLostType();
            }
            // 带版本号、用户ID校验更新，防止并发篡改
            rows = lostPropertyMapper.updateItemInformation(
                    entity.getId(),
                    userId,
                    entity.getVersion(),
                    lostName,
                    lostType
            );
        }

        if (rows != 1) {
            throw new BusinessException(409, "拾物登记保存未成功，请重新查询后确认");
        }

        log.info("拾物登记保存成功，id={}", entity.getId());
        String reply = out.getOutput();
        // 尝试同步向量索引；同步失败给用户提示，不阻塞主流程
        if (!lostPropertyIndexService.syncAfterCommit(entity.getId())) {
            reply += "\n\n拾物登记已保存，检索索引正在同步，稍后可查询到最新线索。";
        }

        return List.of(
                ChatStreamEvent.text(reply),
                ChatStreamEvent.registrationSaved(
                        "found",
                        entity.getId(),
                        out.getAction().name()
                )
        );
    }

    /**
     * 失物登记业务：对话收集信息，完成后落地数据库（新增/修改失物记录）
     *
     * @param sessionId 会话ID
     * @param message   用户消息
     * @return 事件列表
     */
    public List<ChatStreamEvent> registerLost(String sessionId, String message) {
        Long userId = chatConversationService.requireOwnerUserId(sessionId);
        RegistrationContact contact = chatConversationService.requireOwnerContact(sessionId);
        // 调用失物登记助手，多轮对话收集物品信息
        LostRegisterOutput output = lostRegisterAssistant.registerLost(
                sessionId,
                message,
                contact.realName(),
                maskPhone(contact.phone())
        );
        if (output == null) {
            throw new IllegalStateException("丢失登记助手返回内容为空");
        }
        if (!StringUtils.hasText(output.getOutput())) {
            throw new IllegalStateException("丢失登记助手缺少回复内容");
        }
        // 信息未收集完成，继续对话，不入库
        if (!Boolean.TRUE.equals(output.getCompleted())) {
            return List.of(
                    ChatStreamEvent.text(output.getOutput())
            );
        }

        // 信息收集完成，校验操作类型与ID
        validateRegistrationAction(output.getAction(), output.getId());
        Long id = output.getId();
        LostRegisterEntity entity;
        int rows;

        if (id == null) {
            // 新增失物登记
            validateNewRegistrationContact(
                    contact,
                    output.getContactConfirmed()
            );

            entity = new LostRegisterEntity();
            entity.setUserId(userId);
            entity.setUsername(contact.realName());
            entity.setPhone(contact.phone());
            entity.setLostName(output.getLostName());
            entity.setLostType(output.getLostFeature());
            // 失物新建默认状态：寻找中 SEARCHING
            entity.setStatus("SEARCHING");
            entity.setVersion(0L);
            rows = lostRegisterMapper.insert(entity);
        } else {
            // 修改已有失物登记
            if (id <= 0) {
                throw new BusinessException(400, "丢失登记编号不正确");
            }
            entity = lostRegisterMapper.selectOne(
                    new QueryWrapper<LostRegisterEntity>()
                            .eq("id", id)
                            .eq("user_id", userId)
            );
            if (entity == null) {
                throw new BusinessException(
                        404,
                        "丢失登记不存在或无权修改"
                );
            }
            // 已完成/交接的失物不允许修改
            if (!"SEARCHING".equals(entity.getStatus())) {
                throw new BusinessException(
                        409,
                        "当前登记已经进入交接或完成状态，不能继续修改"
                );
            }

            String lostName = entity.getLostName();
            String lostFeature = entity.getLostType();
            if (StringUtils.hasText(output.getLostName())) {
                lostName = output.getLostName();
            }
            if (StringUtils.hasText(output.getLostFeature())) {
                lostFeature = output.getLostFeature();
            }
            rows = lostRegisterMapper.updateItemInformation(
                    entity.getId(),
                    userId,
                    entity.getVersion(),
                    lostName,
                    lostFeature
            );
        }

        if (rows != 1) {
            throw new BusinessException(409, "丢失登记保存未成功，请重新查询后确认");
        }

        log.info("丢失登记保存成功，id={}", entity.getId());
        return List.of(
                ChatStreamEvent.text(output.getOutput()),
                ChatStreamEvent.registrationSaved(
                        "lost",
                        entity.getId(),
                        output.getAction().name()
                )
        );
    }

    /**
     * 修改登记前置校验：手机号不能为空
     *
     * @param phone 手机号
     */
    private void requireUpdatePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalStateException("修改登记前请提供原登记手机号");
        }
    }

    /**
     * 手机号脱敏：展示给AI对话前端，不泄露完整号码
     * 示例：13812345678 → 138****5678
     *
     * @param phone 原始手机号
     * @return 脱敏字符串
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String value = phone.trim();
        if (value.length() < 7) {
            return "******";
        }
        return value.substring(0, 3)
                + "****"
                + value.substring(value.length() - 4);
    }

    /**
     * 新增登记时校验用户信息完整性与用户确认标记
     * 必须：姓名手机号齐全 + 用户确认使用该联系方式
     *
     * @param contact          用户联系信息
     * @param contactConfirmed AI侧识别到的用户确认标记
     */
    private void validateNewRegistrationContact(
            RegistrationContact contact,
            Boolean contactConfirmed
    ) {
        if (!contact.complete()) {
            throw new BusinessException(
                    400,
                    "请先在个人资料中补全真实姓名和手机号，再进行登记"
            );
        }
        if (!Boolean.TRUE.equals(contactConfirmed)) {
            throw new BusinessException(
                    400,
                    "请先确认使用当前账号的联系人信息"
            );
        }
    }

    /**
     * 校验登记操作类型和ID约束：
     * CREATE 不能携带id；UPDATE必须携带合法id
     *
     * @param action 操作类型 CREATE / UPDATE
     * @param id     登记主键ID
     */
    private void validateRegistrationAction(RegistrationAction action, Long id) {
        if (action == null) {
            throw new BusinessException(
                    400,
                    "未能确定是新增还是修改登记，请明确本次操作"
            );
        }
        if (action == RegistrationAction.CREATE && id != null) {
            throw new BusinessException(
                    400,
                    "新增登记不应携带已有记录编号，请重新确认"
            );
        }
        if (action == RegistrationAction.UPDATE
                && (id == null || id <= 0)) {
            throw new BusinessException(
                    400,
                    "尚未确定要修改的登记，请先查询并确认目标记录"
            );
        }
    }

    /**
     * 本地记录：用于封装当前用户失物列表，传给LLM上下文
     * @param id 失物id
     * @param lostName 物品名称
     * @param lostFeature 物品特征/类型
     */
    private record MyLostRegistration(
            Long id,
            String lostName,
            String lostFeature
    ) {
    }
}