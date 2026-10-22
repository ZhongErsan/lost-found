package com.lostsystem.tools;

import com.lostsystem.entity.LostPropertyEntity;
import com.lostsystem.mapper.LostPropertyMapper;
import com.lostsystem.service.ChatConversationService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LostPropertyTools {

    private final LostPropertyMapper lostPropertyMapper;
    private final ChatConversationService chatConversationService;

    @Tool("根据手机号查询当前登录用户自己的拾物登记信息")
    public List<LostPropertyEntity> queryLostPropertyByPhone(
            @ToolMemoryId String sessionId,
            @P("登记时填写的联系手机号") String phone
    ) {
        Long userId =
                chatConversationService.requireOwnerUserId(sessionId);

        if (!StringUtils.hasText(phone)) {
            return List.of();
        }

        return lostPropertyMapper.findAllByUserIdAndPhone(
                userId,
                phone.trim()
        );
    }
    @Tool("查询当前登录用户自己的最近20条拾物登记，用于补充或修改已有拾物登记")
    public List<LostPropertyEntity> queryMyLostProperties(
            @ToolMemoryId String sessionId
    ) {
        Long userId = chatConversationService.requireOwnerUserId(sessionId);

        return lostPropertyMapper.findTop20ByUserIdOrderByIdDesc(userId);
    }
}
