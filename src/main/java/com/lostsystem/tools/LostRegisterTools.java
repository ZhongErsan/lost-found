package com.lostsystem.tools;

import com.lostsystem.entity.LostRegisterEntity;
import com.lostsystem.mapper.LostRegisterMapper;
import com.lostsystem.service.ChatConversationService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
/**
 * 失物登记相关工具集
 * <p>
 * 供大模型调用，查询当前登录用户名下的丢失登记记录，用于修改、补全已有登记信息
 * 依靠 sessionId 校验归属用户，只允许查询用户本人的数据，防止越权
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LostRegisterTools {
    private final LostRegisterMapper lostRegisterMapper;
    private final ChatConversationService chatConversationService;

    /**
     * 根据手机号查询当前登录用户自己的丢失登记信息
     * @param sessionId 会话标识，由框架注入@ToolMemoryId，用于获取所属用户ID，做数据权限控制
     * @param phone     登记时填写的联系手机号，作为过滤条件
     * @return 匹配的丢失登记记录列表；手机号为空时返回空集合
     */
    @Tool("根据手机号查询当前登录用户自己的丢失登记信息")
    public List<LostRegisterEntity> queryLostRegisterByPhone(@ToolMemoryId String sessionId, @P("登记时填写的联系手机号") String phone) {
        // 根据会话拿到归属用户ID，做权限隔离，只能查自己的数据
        Long userId = chatConversationService.requireOwnerUserId(sessionId);

        if (!StringUtils.hasText(phone)) {
            return List.of();
        }

        List<LostRegisterEntity> records = lostRegisterMapper.findAllByUserIdAndPhone(
                userId,
                phone.trim()
        );

        log.info(
                "查询本人丢失登记：方式=手机号，userId={}，命中条数={}",
                userId,
                records.size()
        );
        return records;
    }

    /**
     * 查询当前登录用户最近20条丢失登记记录
     * <p>
     * 多用于用户需要补充信息、修改已有登记的场景，给模型提供历史登记上下文
     * </p>
     *
     * @param sessionId 会话标识，由框架注入@ToolMemoryId，用于获取所属用户ID
     * @return 用户最近20条丢失登记记录，按ID倒序（最新在前）
     */
    @Tool("查询当前登录用户自己的最近20条丢失登记，用于补充或修改已有登记")
    public List<LostRegisterEntity> queryMyLostRegisters(
            @ToolMemoryId String sessionId
    ) {
        Long userId = chatConversationService.requireOwnerUserId(sessionId);

        List<LostRegisterEntity> records = lostRegisterMapper.findTop20ByUserIdOrderByIdDesc(userId);

        log.info(
                "查询本人丢失登记：方式=最近20条，userId={}，命中条数={}",
                userId,
                records.size()
        );
        return records;
    }
}