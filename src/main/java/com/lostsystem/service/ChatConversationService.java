package com.lostsystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lostsystem.auth.RegistrationContact;
import com.lostsystem.entity.ChatConversationEntity;
import com.lostsystem.entity.SysUser;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.ChatConversationMapper;
import com.lostsystem.mapper.SysUserMapper;
import com.lostsystem.vo.ChatConversationListItemVO;
import com.lostsystem.vo.ChatConversationPageVO;
import com.lostsystem.vo.ChatConversationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatConversationService {
    private final SysUserMapper sysUserMapper;
    private final ChatConversationMapper chatConversationMapper;

    /**
     * 为当前登录用户创建指定模式的聊天会话。
     */
    public ChatConversationVO create(
            Long userId,
            String mode
    ) {
        requireUserId(userId);

        if (mode == null
                || !("lost".equals(mode)
                || "found".equals(mode)
                || "search".equals(mode))) {
            throw new BusinessException(
                    400,
                    "会话模式只能是lost、found或search"
            );
        }

        String sessionId = UUID.randomUUID().toString();

        ChatConversationEntity entity =
                new ChatConversationEntity();

        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setMode(mode);

        int rows = chatConversationMapper.insert(entity);

        if (rows != 1) {
            throw new BusinessException(
                    500,
                    "创建聊天会话失败，请稍后重试"
            );
        }

        return new ChatConversationVO(
                sessionId,
                mode
        );
    }

    /**
     * 检查指定会话是否属于当前登录用户。
     *
     * 下一步接入聊天、查询历史和清空历史接口。
     */
    public void requireOwned(String sessionId, Long userId) {
        requireUserId(userId);

        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw new BusinessException(400, "聊天会话标识不正确");
        }

        LambdaQueryWrapper<ChatConversationEntity> query = new LambdaQueryWrapper<>();

        query.eq(ChatConversationEntity::getSessionId, sessionId);

        query.eq(ChatConversationEntity::getUserId, userId);

        Long count = chatConversationMapper.selectCount(query);

        if (count == null || count == 0) {
            /*
             * 会话不存在和会话不属于当前用户，
             * 使用相同的响应，避免暴露别人的会话是否存在。
             */
            throw new BusinessException(
                    404,
                    "聊天会话不存在或无权访问"
            );
        }
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(
                    401,
                    "请先登录"
            );
        }
    }
    /**
     * 根据已经通过入口归属校验的聊天会话，取得其所有者。
     *
     * 用于异步业务和 AI 工具执行。
     * 本方法只解析会话所有者，不能替代 Controller 的登录和归属校验。
     */
    public Long requireOwnerUserId(String sessionId) {
        if (sessionId == null
                || sessionId.isBlank()
                || sessionId.length() > 64) {
            throw new BusinessException(400, "聊天会话标识不正确");
        }

        ChatConversationEntity conversation =
                chatConversationMapper.selectOne(
                        new LambdaQueryWrapper<ChatConversationEntity>()
                                .eq(
                                        ChatConversationEntity::getSessionId,
                                        sessionId
                                )
                );

        if (conversation == null
                || conversation.getUserId() == null
                || conversation.getUserId() <= 0) {
            throw new BusinessException(
                    404,
                    "聊天会话不存在或无权访问"
            );
        }

        return conversation.getUserId();
    }
    public RegistrationContact requireOwnerContact(String sessionId) {
        Long userId = requireOwnerUserId(sessionId);

        SysUser user = sysUserMapper.selectById(userId);

        if (user == null) {
            throw new BusinessException(401, "当前用户不存在");
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(403, "账号已被禁用");
        }

        return new RegistrationContact(
                user.getId(),
                normalize(user.getRealName()),
                normalize(user.getPhone())
        );
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
    /**
     * 查询当前登录用户的聊天会话列表。
     */
    public ChatConversationPageVO listMine(
            Long userId,
            Long beforeId,
            int size
    ) {
        requireUserId(userId);

        if (beforeId != null && beforeId <= 0) {
            throw new BusinessException(
                    400,
                    "分页会话编号必须大于0"
            );
        }

        if (size < 1 || size > 100) {
            throw new BusinessException(
                    400,
                    "每页数量必须在1到100之间"
            );
        }

        List<ChatConversationListItemVO> rows =
                chatConversationMapper.findByUserId(
                        userId,
                        beforeId,
                        size + 1
                );

        boolean hasMore = rows.size() > size;

        List<ChatConversationListItemVO> content =
                List.copyOf(
                        rows.subList(
                                0,
                                Math.min(rows.size(), size)
                        )
                );

        Long nextBeforeId = hasMore
                ? content.get(content.size() - 1).getId()
                : null;

        return new ChatConversationPageVO(
                content,
                hasMore,
                nextBeforeId
        );
    }
    /**
     * 为当前用户自己的旧会话补录模式。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationVO fillMissingMode(
            Long userId,
            String sessionId,
            String mode
    ) {
        requireUserId(userId);

        if (sessionId == null
                || sessionId.isBlank()
                || sessionId.length() > 64) {
            throw new BusinessException(
                    400,
                    "聊天会话标识不正确"
            );
        }

        if (mode == null
                || !("lost".equals(mode)
                || "found".equals(mode)
                || "search".equals(mode))) {
            throw new BusinessException(
                    400,
                    "会话模式只能是lost、found或search"
            );
        }

        ChatConversationEntity conversation =
                chatConversationMapper.findOwnedForUpdate(
                        sessionId,
                        userId
                );

        if (conversation == null) {
            throw new BusinessException(
                    404,
                    "聊天会话不存在或无权访问"
            );
        }

        String existingMode = conversation.getMode();

        // 重复提交相同模式，直接返回已有结果。
        if (mode.equals(existingMode)) {
            return new ChatConversationVO(
                    conversation.getSessionId(),
                    existingMode
            );
        }

        // 只允许补录未知模式，不允许切换已确定的模式。
        if (existingMode != null) {
            throw new BusinessException(
                    409,
                    "该会话已设置业务模式，请新建会话切换业务"
            );
        }

        int rows = chatConversationMapper.fillMissingMode(
                conversation.getId(),
                userId,
                mode
        );

        if (rows != 1) {
            throw new BusinessException(
                    409,
                    "会话模式保存未成功，请刷新后重试"
            );
        }

        return new ChatConversationVO(
                conversation.getSessionId(),
                mode
        );
    }
}