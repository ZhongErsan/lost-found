package com.lostsystem.controller;

import com.lostsystem.auth.CurrentUser;
import com.lostsystem.auth.UserContext;
import com.lostsystem.dto.CreateConversationRequest;
import com.lostsystem.dto.SetConversationModeRequest;
import com.lostsystem.service.ChatConversationService;
import com.lostsystem.vo.ChatConversationPageVO;
import com.lostsystem.vo.ChatConversationVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ChatConversationController {

    private final ChatConversationService chatConversationService;
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChatConversationVO create(
            @Valid @RequestBody CreateConversationRequest request
    ) {
        CurrentUser currentUser = UserContext.require();

        return chatConversationService.create(
                currentUser.userId(),
                request.mode()
        );
    }
    /**
     * 查询当前登录用户的会话列表。
     */
    @GetMapping
    public ResponseEntity<ChatConversationPageVO> listMine(
            @RequestParam(
                    value = "beforeId",
                    required = false
            ) Long beforeId,

            @RequestParam(
                    value = "size",
                    defaultValue = "20"
            ) int size
    ) {
        CurrentUser currentUser = UserContext.require();

        ChatConversationPageVO result =
                chatConversationService.listMine(
                        currentUser.userId(),
                        beforeId,
                        size
                );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }
    /**
     * 为当前用户自己的旧会话补录业务模式。
     */
    @PutMapping("/{sessionId}/mode")
    public ResponseEntity<ChatConversationVO> fillMissingMode(
            @PathVariable("sessionId") String sessionId,
            @Valid @RequestBody SetConversationModeRequest request
    ) {
        CurrentUser currentUser = UserContext.require();

        ChatConversationVO result =
                chatConversationService.fillMissingMode(
                        currentUser.userId(),
                        sessionId,
                        request.mode()
                );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }
}