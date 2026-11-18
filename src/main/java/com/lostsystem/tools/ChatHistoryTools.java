package com.lostsystem.tools;

import com.lostsystem.bean.ChatHistory;
import com.lostsystem.entity.ChatHistoryEntity;
import com.lostsystem.mapper.ChatHistoryMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话历史工具类
 * <p>
 * 作为 Spring AI 工具函数，供大模型调用，读取当前会话的历史对话记录
 * 绑定 @ToolMemoryId，会话ID由框架自动注入，模型不能自行传入sessionId
 * 从数据库查询该会话最近20条对话，调整时序后返回给模型上下文使用
 * </p>
 */
@Component
@RequiredArgsConstructor
public class ChatHistoryTools {

    /**
     * 对话历史Mapper，数据库交互层，查询chat_history表数据
     */
    private final ChatHistoryMapper chatHistoryMapper;

    /**
     * 工具方法：获取当前会话的历史对话
     * <p>
     * 注解@Tool：声明这是可供LLM调用的工具，描述文本会发给大模型，告知工具用途
     * <p>
     * 安全约束：sessionId 由 Spring AI 框架根据 @MemoryId 自动注入，
     * 模型无权自行生成、修改、传入sessionId，防止越权查询其他会话记录
     *
     * @param sessionId 会话唯一标识，由框架注入，@ToolMemoryId 标记
     * @return 对话历史列表，时序为正序（最早消息在前，最新在后）；参数非法返回空集合
     */
    @Tool("获取当前会话的历史对话")
    public List<ChatHistory> getChatHistory(@ToolMemoryId String sessionId) {
        // 参数校验：空串、空白、过长直接返回空列表，防止异常与非法查询
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            return List.of();
        }
        // SQL：按id倒序查询，拿到最新20条记录（数据库里新数据id更大）
        List<ChatHistoryEntity> entities = new ArrayList<>(
                chatHistoryMapper.findTop20BySessionIdOrderByIdDesc(sessionId)
        );
        // 数据库结果是【新消息在前】，反转后变成【旧消息在前、新消息在后】，符合LLM上下文顺序
        Collections.reverse(entities);
        // Entity -> 对外传输的ChatHistory DTO转换
        return entities.stream()
                .map(this::toChatHistory)
                .toList();
    }

    /**
     * 数据库实体转工具出参DTO
     * <p>
     * 兼容数据库历史编码：0=user 用户消息，1=assistant AI回复
     * 其他原始role值直接透传，便于后续扩展system等角色
     *
     * @param entity 数据库存储实体
     * @return 给模型使用的对话记录对象
     */
    private ChatHistory toChatHistory(ChatHistoryEntity entity) {
        ChatHistory history = new ChatHistory();
        String role = entity.getRole();
        if ("0".equals(role)) {
            history.setRole("user");
        } else if ("1".equals(role)) {
            history.setRole("assistant");
        } else {
            history.setRole(role);
        }
        history.setContent(entity.getContent());
        return history;
    }
}