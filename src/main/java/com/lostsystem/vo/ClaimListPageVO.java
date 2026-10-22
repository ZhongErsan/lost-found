package com.lostsystem.vo;

import java.util.List;

public record ClaimListPageVO(
        List<ClaimListItemVO> content,
        boolean hasMore,
        Long nextBeforeId
) {
}