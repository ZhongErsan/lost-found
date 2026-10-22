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

@Component
@RequiredArgsConstructor
public class ChatHistoryTools {

    private final ChatHistoryMapper chatHistoryMapper;

    /**
     * 只读取当前 AI 调用所对应的会话历史。
     * sessionId 由 LangChain4j 从 @MemoryId 注入，
     * 不允许模型自行指定。
     */
    @Tool("获取当前会话的历史对话")
    public List<ChatHistory> getChatHistory(
            @ToolMemoryId String sessionId) {

        if (sessionId == null
                || sessionId.isBlank()
                || sessionId.length() > 64) {
            return List.of();
        }

        List<ChatHistoryEntity> entities = new ArrayList<>(
                chatHistoryMapper
                        .findTop20BySessionIdOrderByIdDesc(sessionId)
        );

        // SQL 按 id 倒序取最新 20 条；
        // 交给模型前恢复为时间正序。
        Collections.reverse(entities);
        return entities.stream()
                .map(this::toChatHistory)
                .toList();
    }

    private ChatHistory toChatHistory(ChatHistoryEntity entity) {
        ChatHistory history = new ChatHistory();
        // 数据库继续兼容现有的 0/1 角色编码，
        // 给模型返回更明确的角色名称。
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
