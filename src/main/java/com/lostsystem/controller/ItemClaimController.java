package com.lostsystem.controller;

import com.lostsystem.auth.CurrentUser;
import com.lostsystem.auth.UserContext;
import com.lostsystem.dto.CreateClaimRequest;
import com.lostsystem.dto.RejectClaimRequest;
import com.lostsystem.service.ItemClaimService;
import com.lostsystem.vo.ClaimContactVO;
import com.lostsystem.vo.ClaimDetailVO;
import com.lostsystem.vo.ClaimListPageVO;
import com.lostsystem.vo.ClaimSubmissionVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/claims")
@RequiredArgsConstructor
public class ItemClaimController {

    private final ItemClaimService itemClaimService;

    @PostMapping
    public ClaimSubmissionVO create(@Valid @RequestBody CreateClaimRequest request) {
        CurrentUser currentUser = UserContext.require();
        return itemClaimService.create(currentUser.userId(), request);
    }
    /**
     * 我提交的认领申请。
     */
    @GetMapping("/mine")
    public ClaimListPageVO mine(
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        CurrentUser currentUser = UserContext.require();
        return itemClaimService.listMine(currentUser.userId(), beforeId, size);
    }

    /**
     * 我收到的认领申请。
     */
    @GetMapping("/incoming")
    public ClaimListPageVO incoming(
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        CurrentUser currentUser = UserContext.require();
        return itemClaimService.listIncoming(currentUser.userId(), beforeId, size);
    }
    /**
     * 拾到者同意认领申请。
     */
    @PostMapping("/{claimId}/approve")
    public ClaimSubmissionVO approve(@PathVariable("claimId") Long claimId) {
        CurrentUser currentUser = UserContext.require();
        return itemClaimService.approve(currentUser.userId(), claimId);
    }

    /**
     * 拾到者拒绝认领申请。
     */
    @PostMapping("/{claimId}/reject")
    public ClaimSubmissionVO reject(@PathVariable("claimId") Long claimId, @Valid @RequestBody RejectClaimRequest request) {
        CurrentUser currentUser = UserContext.require();
        return itemClaimService.reject(currentUser.userId(), claimId, request);
    }
    /**
     * 拾到者确认交接。
     */
    @PostMapping("/{claimId}/handover")
    public ClaimSubmissionVO handover(
            @PathVariable("claimId") Long claimId
    ) {
        CurrentUser currentUser = UserContext.require();

        return itemClaimService.handover(
                currentUser.userId(),
                claimId
        );
    }

    /**
     * 申请人确认收到。
     */
    @PostMapping("/{claimId}/receive")
    public ClaimSubmissionVO receive(
            @PathVariable("claimId") Long claimId
    ) {
        CurrentUser currentUser = UserContext.require();

        return itemClaimService.receive(
                currentUser.userId(),
                claimId
        );
    }
    /**
     * 申请人取消尚未交接的认领申请。
     */
    @PostMapping("/{claimId}/cancel")
    public ClaimSubmissionVO cancel(
            @PathVariable("claimId") Long claimId
    ) {
        CurrentUser currentUser = UserContext.require();

        return itemClaimService.cancel(
                currentUser.userId(),
                claimId
        );
    }
    /**
     * 查看认领申请详情。
     */
    @GetMapping("/{claimId}")
    public ResponseEntity<ClaimDetailVO> detail(
            @PathVariable("claimId") Long claimId
    ) {
        CurrentUser currentUser = UserContext.require();

        ClaimDetailVO detail = itemClaimService.detail(
                currentUser.userId(),
                claimId
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(detail);
    }

    /**
     * 获批后查看对方联系方式。
     */
    @GetMapping("/{claimId}/contact")
    public ResponseEntity<ClaimContactVO> contact(
            @PathVariable("claimId") Long claimId
    ) {
        CurrentUser currentUser = UserContext.require();

        ClaimContactVO contact = itemClaimService.contact(
                currentUser.userId(),
                claimId
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(contact);
    }
}