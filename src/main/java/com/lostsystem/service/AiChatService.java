package com.lostsystem.service;

import com.lostsystem.dto.ChatStreamEvent;
import com.lostsystem.vo.ChatHistoryVO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;

import java.util.List;

public interface AiChatService {
    Flux<ChatStreamEvent> chatStream(String sessionId, String message);

    Page<ChatHistoryVO> queryChatHistory(String sessionId, Pageable page);

    void clearChatHistory(String sessionId);

    String embeddingIndex();

    List<String> embeddingQuery(String message);
}
