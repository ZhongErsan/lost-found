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

/**
 * 聊天会话业务服务
 * 负责会话创建、会话归属权限校验、会话分页查询、会话模式补录、获取会话关联用户信息
 * 会话模式分为：lost（失物）、found（招领）、search（检索）
 */
@Service
@RequiredArgsConstructor
public class ChatConversationService {

    /**
     * 系统用户Mapper，用于查询用户基础信息、联系方式、账号状态
     */
    private final SysUserMapper sysUserMapper;
    /**
     * 聊天会话Mapper，操作会话主表数据
     */
    private final ChatConversationMapper chatConversationMapper;

    /**
     * 为当前登录用户创建指定模式的聊天会话。
     *
     * @param userId 创建会话的用户ID
     * @param mode   会话业务模式，仅支持 lost / found / search
     * @return 会话VO，包含生成的sessionId与模式
     */
    public ChatConversationVO create(
            Long userId,
            String mode
    ) {
        // 校验用户ID合法性
        requireUserId(userId);

        // 校验会话模式，只允许规定的三种业务类型
        if (mode == null
                || !("lost".equals(mode)
                || "found".equals(mode)
                || "search".equals(mode))) {
            throw new BusinessException(
                    400,
                    "会话模式只能是lost、found或search"
            );
        }

        // 使用UUID生成全局唯一会话标识sessionId
        String sessionId = UUID.randomUUID().toString();

        ChatConversationEntity entity =
                new ChatConversationEntity();

        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setMode(mode);

        // 插入会话记录
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
     * 安全设计：会话不存在 和 不属于该用户 返回相同提示，防止遍历探测会话
     *
     * @param sessionId 会话唯一标识
     * @param userId    当前操作用户ID
     */
    public void requireOwned(String sessionId, Long userId) {
        requireUserId(userId);
        // sessionId格式合法性校验
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw new BusinessException(400, "聊天会话标识不正确");
        }

        // 同时匹配sessionId + userId，判断用户是否拥有这个会话
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

    /**
     * 校验用户ID：非空且大于0
     *
     * @param userId 用户ID
     */
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
     * <p>
     * 用于异步业务和 AI 工具执行。
     * 本方法只解析会话所有者，不能替代 Controller 的登录和归属校验。
     * 注意：异步线程拿不到ThreadLocal用户上下文时，通过sessionId反查用户ID
     *
     * @param sessionId 会话标识
     * @return 会话所属用户ID
     */
    public Long requireOwnerUserId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw new BusinessException(400, "聊天会话标识不正确");
        }

        // 根据sessionId查询会话记录
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

    /**
     * 根据会话ID获取会话所有者的联系信息（姓名、手机号）
     * 适用于AI工具调用、失物招领场景获取用户联系方式
     *
     * @param sessionId 会话标识
     * @return 封装用户id、姓名、手机号的联系对象
     */
    public RegistrationContact requireOwnerContact(String sessionId) {
        // 通过sessionId拿到所属用户ID
        Long userId = requireOwnerUserId(sessionId);

        // 查询用户基础信息
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, "当前用户不存在");
        }

        // 校验账号状态，禁用账号不允许获取联系信息
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(403, "账号已被禁用");
        }

        return new RegistrationContact(
                user.getId(),
                normalize(user.getRealName()),
                normalize(user.getPhone())
        );
    }

    /**
     * 文本标准化处理：去除首尾空白，空字符串转为null
     * 统一姓名、手机号存储格式
     *
     * @param value 原始字符串
     * @return 处理后的字符串
     */
    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 查询当前登录用户的聊天会话列表。
     * 采用游标分页（beforeId），不使用offset分页，适合会话列表滚动加载
     *
     * @param userId   当前用户ID
     * @param beforeId 游标：上一页最后一条会话主键ID，null表示第一页
     * @param size     期望每页条数
     * @return 分页VO：会话列表、是否有下一页、下一页游标
     */
    public ChatConversationPageVO listMine(
            Long userId,
            Long beforeId,
            int size
    ) {
        requireUserId(userId);

        // 游标参数合法性校验
        if (beforeId != null && beforeId <= 0) {
            throw new BusinessException(
                    400,
                    "分页会话编号必须大于0"
            );
        }

        // 分页条数范围限制
        if (size < 1 || size > 100) {
            throw new BusinessException(
                    400,
                    "每页数量必须在1到100之间"
            );
        }

        // 查询 size+1 条，用来判断是否还有更多数据（游标分页经典做法）
        List<ChatConversationListItemVO> rows =
                chatConversationMapper.findByUserId(
                        userId,
                        beforeId,
                        size + 1
                );

        // 是否存在下一页标记
        boolean hasMore = rows.size() > size;
        // 截断到需要的条数
        List<ChatConversationListItemVO> content =
                List.copyOf(
                        rows.subList(
                                0,
                                Math.min(rows.size(), size)
                        )
                );
        // 下一页游标：当前页最后一条的主键ID；没有更多则为null
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
     * <p>
     * 场景：早期创建的会话没有mode字段，支持一次性补录；
     * 约束：已经设置过mode的会话不允许修改、切换模式，必须新建会话
     * 加事务，异常回滚
     *
     * @param userId    用户ID
     * @param sessionId 会话标识
     * @param mode      需要补录的业务模式 lost/found/search
     * @return 更新后的会话VO
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

        // 行锁查询：锁定这条会话记录，防止并发补录冲突
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
        // 重复提交相同模式，直接返回已有结果，不执行更新
        if (mode.equals(existingMode)) {
            return new ChatConversationVO(
                    conversation.getSessionId(),
                    existingMode
            );
        }

        // 核心约束：已经存在模式的会话，禁止修改切换模式
        if (existingMode != null) {
            throw new BusinessException(
                    409,
                    "该会话已设置业务模式，请新建会话切换业务"
            );
        }

        // 执行mode补录更新
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