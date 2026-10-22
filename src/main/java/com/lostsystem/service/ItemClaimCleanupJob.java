package com.lostsystem.service;

import com.lostsystem.mapper.ItemClaimMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ItemClaimCleanupJob {

    private final ItemClaimMapper itemClaimMapper;

    /**
     * 启动5秒后执行第一次，
     * 后续每轮结束30秒后再次执行。
     *
     * 每轮最多处理100条。
     */
    @Scheduled(
            fixedDelayString =
                    "${lost-found.claim-cleanup.delay-ms:30000}",
            initialDelayString =
                    "${lost-found.claim-cleanup.initial-delay-ms:5000}"
    )
    public void closeInvalidPendingClaims() {
        List<Long> claimIds;

        try {
            claimIds = itemClaimMapper.findPendingToClose(100);
        } catch (Exception ex) {
            log.error("查询失效认领申请失败，等待下一轮重试", ex);
            return;
        }

        int closedCount = 0;

        for (Long claimId : claimIds) {
            try {
                /*
                 * 更新时再次限制 status = PENDING。
                 *
                 * 已经被其他请求处理的申请不会被覆盖。
                 * 每条更新独立提交，失败项由下一轮重试。
                 */
                closedCount += itemClaimMapper.cancelPending(
                        claimId
                );
            } catch (Exception ex) {
                log.error(
                        "关闭失效认领申请失败，claimId={}，等待下一轮重试",
                        claimId,
                        ex
                );
            }
        }

        if (closedCount > 0) {
            log.info(
                    "已关闭失效认领申请，数量={}",
                    closedCount
            );
        }
    }
}