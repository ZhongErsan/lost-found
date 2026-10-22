package com.lostsystem.vo;

import java.util.List;

public record ChatConversationPageVO(
        List<ChatConversationListItemVO> content,
        boolean hasMore,
        Long nextBeforeId
) {
}