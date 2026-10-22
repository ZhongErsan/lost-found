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

@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {
    @Autowired
    private AiIntentionAssistant aiIntentionAssistant;
    @Autowired
    private LostRegisterAssistant lostRegisterAssistant;
    @Autowired
    private LostRegisterMapper lostRegisterMapper;
    @Autowired
    private ChatHistoryMapper chatHistoryMapper;
    @Autowired
    private ConversationMemory conversationMemory;
    @Autowired
    private LostPropertyAssistant lostPropertyAssistant;
    @Autowired
    private LostPropertyIndexService lostPropertyIndexService;

    private static final Map<String, String> HISTORY_SORT_COLUMNS = Map.of(
            "id", "id", "createdDate", "created_date", "updatedDate", "updated_date");
    @Autowired
    private LostPropertyMapper lostPropertyMapper;
    @Autowired
    private ChatConversationService chatConversationService;
    @Override
    public Page<ChatHistoryVO> queryChatHistory(String sessionId, Pageable page) {
        requireSessionId(sessionId);
        if (page == null || page.isUnpaged() || page.getPageSize() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每页记录数必须在 1 到 100 之间");
        }
        QueryWrapper<ChatHistoryEntity> query = new QueryWrapper<>();
        query.eq("session_id", sessionId);
        Sort sort = page.getSort().isSorted() ? page.getSort() : Sort.by(Sort.Direction.DESC, "id");
        for (Sort.Order order : sort) {
            String column = HISTORY_SORT_COLUMNS.get(order.getProperty());
            if (column == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持按 id、createdDate、updatedDate 排序");
            }
            query.orderBy(true, order.isAscending(), column);
        }
        // A unique tie-breaker keeps pages stable when timestamps are equal.
        if (sort.getOrderFor("id") == null) query.orderByDesc("id");
        long total = chatHistoryMapper.selectCount(new QueryWrapper<ChatHistoryEntity>().eq("session_id", sessionId));
        if (page.getOffset() >= total) return new PageImpl<>(List.of(), page, total);
        // Values come from Pageable, never from raw SQL supplied by the client.
        // Explicit LIMIT/OFFSET works without adding a MyBatis pagination interceptor.
        query.last("LIMIT " + page.getPageSize() + " OFFSET " + page.getOffset());
        List<ChatHistoryVO> records = chatHistoryMapper.selectList(query).stream()
                .map(ChatHistoryVO::from).toList();
        return new PageImpl<>(records, page, total);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearChatHistory(String sessionId) {
        requireSessionId(sessionId);
        chatHistoryMapper.delete(new QueryWrapper<ChatHistoryEntity>().eq("session_id", sessionId));
        conversationMemory.clear(sessionId);
    }

    @Override
    public String embeddingIndex() {
        try {
            return "success，索引记录数=" + lostPropertyIndexService.reconcile();
        } catch (java.io.IOException ex) {
            throw new BusinessException(503, "拾物索引同步失败，请稍后重试", ex);
        }
    }

    @Override

    public List<String> embeddingQuery(String message) {
        return lostPropertyIndexService.search(message);
    }

    private void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId（当前会话标识）不能为空");
        }
    }

    @Override
    @ChatFlow
    public Flux<ChatStreamEvent> chatStream(String sessionId, String message) {
        requireSessionId(sessionId);

        if (message == null || message.isBlank()) {
            return Flux.error(new IllegalArgumentException("消息不能为空"));
        }

        return Mono.fromCallable(() -> {
                    IntentionOutput result =
                            aiIntentionAssistant.intention(sessionId, message);

                    if (result == null) {
                        throw new IllegalStateException("意图识别助手返回内容为空");
                    }

                    return result;
                })
                .flatMapMany(result ->
                        streamByIntention(sessionId, message, result))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 根据意图选择对应的业务。
     *
     * 1、2 是同步的结构化输出，完成后发送一条回复。
     * 3 直接返回模型的 Flux，不再等待整个回答结束。
     * 其他意图沿用意图识别结果中的 output。
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
                return Mono.fromCallable(() -> registerLost(sessionId, message))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(Flux::fromIterable);

            case 2:
                return Mono.fromCallable(() ->
                                findLostPropertyRegister(sessionId, message))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(Flux::fromIterable);

            case 3:
                return queryLostProperty(sessionId, message)
                        .map(ChatStreamEvent::text);

            default:
                return output == null
                        ? Flux.empty()
                        : Flux.just(ChatStreamEvent.text(output));
        }
    }
    private Flux<String> queryLostProperty(String sessionId, String message) {
        return Mono.fromCallable(() -> {
                    Long userId =
                            chatConversationService.requireOwnerUserId(sessionId);

                    List<MyLostRegistration> registrations =
                            lostRegisterMapper
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

                    return JsonUtils.toJson(registrations);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(registrationsJson ->
                        lostPropertyAssistant.queryLostProperty(
                                sessionId,
                                message,
                                registrationsJson
                        )
                )
                .doOnComplete(() ->
                        log.info(
                                "失物查询流式回复完成，sessionId={}",
                                sessionId
                        )
                );
    }

    private List<ChatStreamEvent> findLostPropertyRegister(String sessionId, String message) {
        Long userId = chatConversationService.requireOwnerUserId(sessionId);

        RegistrationContact contact = chatConversationService.requireOwnerContact(sessionId);

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
        if (!Boolean.TRUE.equals(out.getCompleted())) {
            return List.of(
                    ChatStreamEvent.text(out.getOutput())
            );
        }
        validateRegistrationAction(out.getAction(), out.getId());
        Long id = out.getId();
        LostPropertyEntity entity;
        int rows;
        if (id == null) {
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
            // 新增拾物登记
            entity.setStatus("AVAILABLE");
            entity.setVersion(0L);
            rows = lostPropertyMapper.insert(entity);
        }else {
            if (id <= 0) {
                throw new BusinessException(400, "拾物登记编号不正确");
            }
            // 读取时就限定所有者，其他用户的记录不可见。
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


            // 写入时再次限定所有者，不能改回 updateById。
            if (!"AVAILABLE".equals(entity.getStatus())) {
                throw new BusinessException(
                        409,
                        "当前拾物登记已经进入交接或归还状态，不能继续修改"
                );
            }

            String lostName = entity.getLostName();
            String lostType = entity.getLostType();

            if (StringUtils.hasText(out.getLostName())) {
                lostName = out.getLostName();
            }

            if (StringUtils.hasText(out.getLostType())) {
                lostType = out.getLostType();
            }

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
    public List<ChatStreamEvent> registerLost(String sessionId, String message) {
        Long userId = chatConversationService.requireOwnerUserId(sessionId);
        RegistrationContact contact = chatConversationService.requireOwnerContact(sessionId);
        LostRegisterOutput output =
                lostRegisterAssistant.registerLost(
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
        if (!Boolean.TRUE.equals(output.getCompleted())) {
            return List.of(
                    ChatStreamEvent.text(output.getOutput())
            );
        }

// 只有准备实际保存时，才要求操作类型和ID完整一致。
        validateRegistrationAction(output.getAction(), output.getId());
        Long id = output.getId();
        LostRegisterEntity entity;
        int rows;
        if (id == null) {
            validateNewRegistrationContact(
                    contact,
                    output.getContactConfirmed()
            );

            entity = new LostRegisterEntity();

            entity.setUserId(userId);

            // 使用后端从 sys_user 查询出的真实联系人信息。
            entity.setUsername(contact.realName());
            entity.setPhone(contact.phone());

            // 物品信息由 AI 整理。
            entity.setLostName(output.getLostName());
            entity.setLostType(output.getLostFeature());
            // 新增丢失登记
            entity.setStatus("SEARCHING");
            entity.setVersion(0L);
            rows = lostRegisterMapper.insert(entity);
        } else {
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

    private void requireUpdatePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalStateException("修改登记前请提供原登记手机号");
        }
    }
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
     * 新增登记前，校验账号联系人信息及用户确认状态。
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
     * 校验操作类型与记录ID是否一致。
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
    private record MyLostRegistration(
            Long id,
            String lostName,
            String lostFeature
    ) {
    }
}
