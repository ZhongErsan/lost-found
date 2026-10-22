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
@Slf4j
@Component
@RequiredArgsConstructor
public class LostRegisterTools {

    private final LostRegisterMapper lostRegisterMapper;
    private final ChatConversationService chatConversationService;

    @Tool("根据手机号查询当前登录用户自己的丢失登记信息")
    public List<LostRegisterEntity> queryLostRegisterByPhone(
            @ToolMemoryId String sessionId,
            @P("登记时填写的联系手机号") String phone
    ) {
        Long userId =
                chatConversationService.requireOwnerUserId(sessionId);

        if (!StringUtils.hasText(phone)) {
            return List.of();
        }

        List<LostRegisterEntity> records =
                lostRegisterMapper.findAllByUserIdAndPhone(
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
    @Tool("查询当前登录用户自己的最近20条丢失登记，用于补充或修改已有登记")
    public List<LostRegisterEntity> queryMyLostRegisters(
            @ToolMemoryId String sessionId
    ) {
        Long userId =
                chatConversationService.requireOwnerUserId(sessionId);

        List<LostRegisterEntity> records =
                lostRegisterMapper.findTop20ByUserIdOrderByIdDesc(userId);

        log.info(
                "查询本人丢失登记：方式=最近20条，userId={}，命中条数={}",
                userId,
                records.size()
        );

        return records;
    }
}