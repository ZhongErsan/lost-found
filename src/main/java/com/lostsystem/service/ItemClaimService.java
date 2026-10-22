package com.lostsystem.service;

import com.lostsystem.dto.CreateClaimRequest;
import com.lostsystem.dto.RejectClaimRequest;
import com.lostsystem.entity.ItemClaimEntity;
import com.lostsystem.entity.LostPropertyEntity;
import com.lostsystem.entity.LostRegisterEntity;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.ItemClaimMapper;
import com.lostsystem.mapper.LostPropertyMapper;
import com.lostsystem.mapper.LostRegisterMapper;
import com.lostsystem.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ItemClaimService {

    private final LostPropertyMapper lostPropertyMapper;

    private final LostRegisterMapper lostRegisterMapper;

    private final ItemClaimMapper itemClaimMapper;
    private final LostPropertyIndexService lostPropertyIndexService;
    /**
     * 当前操作要求的身份。
     */
    private enum ClaimActor {FINDER, CLAIMANT}
    @Transactional(rollbackFor = Exception.class)
    public ClaimSubmissionVO create(Long userId, CreateClaimRequest request) {
        validateRequest(userId, request);
        /*
         * 统一加锁顺序：
         * 拾物登记 -> 丢失登记 -> 认领申请。
         *
         * 后续审核、取消和确认收货等操作，也沿用这个顺序。
         */
        LostPropertyEntity property = lostPropertyMapper.findByIdForUpdate(request.lostPropertyId());
        if (property == null) {
            throw new BusinessException(404, "拾物登记不存在");
        }

        if (property.getUserId() == null
                || property.getUserId() <= 0) {
            throw new BusinessException(
                    409,
                    "该拾物登记尚未关联登记用户，暂不支持在线认领"
            );
        }

        if (userId.equals(property.getUserId())) {
            throw new BusinessException(
                    400,
                    "不能认领自己登记的拾得物品"
            );
        }

        LostRegisterEntity registration =
                lostRegisterMapper.findOwnedByIdForUpdate(
                        request.lostRegisterId(),
                        userId
                );

        if (registration == null) {
            throw new BusinessException(
                    404,
                    "丢失登记不存在或不属于当前用户"
            );
        }

        /*
         * 先判断重复提交，再判断物品状态。
         *
         * 即使原申请已经获批、物品进入交接状态，
         * 重复提交仍然返回原来的申请。
         */
        ItemClaimEntity existing =
                itemClaimMapper.findActiveForUpdate(
                        property.getId(),
                        registration.getId(),
                        userId
                );

        if (existing != null) {
            return ClaimSubmissionVO.from(existing);
        }

        if (!"AVAILABLE".equals(property.getStatus())) {
            throw new BusinessException(
                    409,
                    "该拾得物品当前不可申请认领"
            );
        }

        if (!"SEARCHING".equals(registration.getStatus())) {
            throw new BusinessException(
                    409,
                    "只有寻找中的丢失登记才能申请认领"
            );
        }

        ItemClaimEntity claim = new ItemClaimEntity();

        claim.setLostPropertyId(property.getId());
        claim.setLostRegisterId(registration.getId());
        claim.setClaimantUserId(userId);
        claim.setVerificationText(
                request.verificationText().strip()
        );
        claim.setStatus("PENDING");

        int rows = itemClaimMapper.insert(claim);

        if (rows != 1 || claim.getId() == null) {
            throw new BusinessException(
                    500,
                    "提交认领申请失败，请稍后重试"
            );
        }

        return ClaimSubmissionVO.from(claim);
    }

    private void validateRequest(
            Long userId,
            CreateClaimRequest request
    ) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录");
        }

        if (request == null) {
            throw new BusinessException(
                    400,
                    "认领申请不能为空"
            );
        }

        if (request.lostPropertyId() == null
                || request.lostPropertyId() <= 0) {
            throw new BusinessException(
                    400,
                    "拾物登记编号不正确"
            );
        }

        if (request.lostRegisterId() == null
                || request.lostRegisterId() <= 0) {
            throw new BusinessException(
                    400,
                    "丢失登记编号不正确"
            );
        }

        String text = request.verificationText();

        if (text == null || text.isBlank()) {
            throw new BusinessException(
                    400,
                    "请填写认领依据"
            );
        }

        if (text.length() > 1000) {
            throw new BusinessException(
                    400,
                    "认领依据不能超过1000个字符"
            );
        }
    }
    /**
     * 我提交的认领申请。
     */
    public ClaimListPageVO listMine(
            Long userId,
            Long beforeId,
            int size
    ) {
        validateListRequest(userId, beforeId, size);

        List<ClaimListItemVO> rows = itemClaimMapper.findMine(
                userId,
                beforeId,
                size + 1
        );

        return buildListPage(rows, size);
    }

    /**
     * 我收到的认领申请。
     */
    public ClaimListPageVO listIncoming(
            Long userId,
            Long beforeId,
            int size
    ) {
        validateListRequest(userId, beforeId, size);

        List<ClaimListItemVO> rows = itemClaimMapper.findIncoming(
                userId,
                beforeId,
                size + 1
        );

        return buildListPage(rows, size);
    }

    private void validateListRequest(
            Long userId,
            Long beforeId,
            int size
    ) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录");
        }

        if (beforeId != null && beforeId <= 0) {
            throw new BusinessException(
                    400,
                    "分页申请编号必须大于0"
            );
        }

        if (size < 1 || size > 100) {
            throw new BusinessException(
                    400,
                    "每页数量必须在1到100之间"
            );
        }
    }

    private ClaimListPageVO buildListPage(
            List<ClaimListItemVO> rows,
            int size
    ) {
        boolean hasMore = rows.size() > size;

        List<ClaimListItemVO> content = List.copyOf(
                rows.subList(
                        0,
                        Math.min(rows.size(), size)
                )
        );

        Long nextBeforeId = hasMore
                ? content.get(content.size() - 1).getId()
                : null;

        return new ClaimListPageVO(
                content,
                hasMore,
                nextBeforeId
        );
    }
    /**
     * 保存已经加锁并完成归属检查的数据。
     */
    private record ClaimReviewContext(
            LostPropertyEntity property,
            LostRegisterEntity registration,
            ItemClaimEntity claim
    ) {
    }
    @Transactional(rollbackFor = Exception.class)
    public ClaimSubmissionVO approve(
            Long userId,
            Long claimId
    ) {
        ClaimReviewContext context =
                lockForReview(userId, claimId);

        LostPropertyEntity property = context.property();
        LostRegisterEntity registration = context.registration();
        ItemClaimEntity claim = context.claim();

        /*
         * 已经同意过的申请，重复点击直接返回当前状态。
         * 不重复更新物品，也不重置审核时间。
         */
        if ("APPROVED".equals(claim.getStatus())
                || "HANDOVER_PENDING".equals(claim.getStatus())
                || "COMPLETED".equals(claim.getStatus())) {
            return ClaimSubmissionVO.from(claim);
        }

        if (!"PENDING".equals(claim.getStatus())) {
            throw new BusinessException(
                    409,
                    "只有待审核申请才能同意"
            );
        }

        if (!"AVAILABLE".equals(property.getStatus())) {
            throw new BusinessException(
                    409,
                    "该拾得物品已被预留、归还或关闭，不能同意此申请"
            );
        }

        if (!"SEARCHING".equals(registration.getStatus())) {
            throw new BusinessException(
                    409,
                    "申请人的丢失登记已进入其他流程，不能同意此申请"
            );
        }

        int propertyRows = lostPropertyMapper.reserveForClaim(
                property.getId(),
                userId,
                property.getVersion()
        );

        if (propertyRows != 1) {
            throw new BusinessException(
                    409,
                    "拾物登记状态发生变化，请刷新后重试"
            );
        }

        int registrationRows = lostRegisterMapper.startHandover(
                registration.getId(),
                claim.getClaimantUserId(),
                registration.getVersion()
        );

        if (registrationRows != 1) {
            throw new BusinessException(
                    409,
                    "丢失登记状态发生变化，请刷新后重试"
            );
        }

        int claimRows = itemClaimMapper.approvePending(
                claim.getId()
        );

        if (claimRows != 1) {
            throw new BusinessException(
                    409,
                    "申请已被处理，请刷新后重试"
            );
        }

        /*
         * 事务提交成功后，重新读取数据库并同步索引。
         * RESERVED 状态的拾物会从可匹配索引中移除。
         */
        lostPropertyIndexService.syncAfterCommit(
                property.getId()
        );

        claim.setStatus("APPROVED");

        return ClaimSubmissionVO.from(claim);
    }
    /**
     * 保留原入口，现有 approve、reject 不需要修改。
     */
    private ClaimReviewContext lockForReview(
            Long userId,
            Long claimId
    ) {
        return lockForClaim(
                userId,
                claimId,
                ClaimActor.FINDER
        );
    }
    /**
     * 按固定顺序加锁，并检查当前操作人的身份。
     *
     * 加锁顺序：
     * 拾物登记 -> 丢失登记 -> 认领申请。
     *
     * 只能从事务方法中调用。
     */
    private ClaimReviewContext lockForClaim(
            Long userId,
            Long claimId,
            ClaimActor actor
    ) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录");
        }

        if (claimId == null || claimId <= 0) {
            throw new BusinessException(
                    400,
                    "认领申请编号不正确"
            );
        }

        // 普通查询只用于定位关联记录。
        ItemClaimEntity initial =
                itemClaimMapper.selectById(claimId);

        if (initial == null) {
            throw new BusinessException(
                    404,
                    "认领申请不存在或无权操作"
            );
        }

        // 申请人操作时，先检查申请归属。
        if (actor == ClaimActor.CLAIMANT
                && !userId.equals(initial.getClaimantUserId())) {
            throw new BusinessException(
                    404,
                    "认领申请不存在或无权操作"
            );
        }

        // 1. 锁定拾物登记。
        LostPropertyEntity property =
                lostPropertyMapper.findByIdForUpdate(
                        initial.getLostPropertyId()
                );

        if (property == null || property.getUserId() == null) {
            throw new BusinessException(
                    404,
                    "认领申请不存在或无权操作"
            );
        }

        // 拾到者操作时，检查拾物登记归属。
        if (actor == ClaimActor.FINDER
                && !userId.equals(property.getUserId())) {
            throw new BusinessException(
                    404,
                    "认领申请不存在或无权操作"
            );
        }

        // 2. 锁定申请人自己的丢失登记。
        LostRegisterEntity registration =
                lostRegisterMapper.findOwnedByIdForUpdate(
                        initial.getLostRegisterId(),
                        initial.getClaimantUserId()
                );

        // 3. 锁定申请，读取最新状态。
        ItemClaimEntity claim =
                itemClaimMapper.findByIdForUpdate(claimId);

        if (claim == null) {
            throw new BusinessException(
                    404,
                    "认领申请不存在或无权操作"
            );
        }

        if (!Objects.equals(
                claim.getLostPropertyId(),
                initial.getLostPropertyId()
        ) || !Objects.equals(
                claim.getLostRegisterId(),
                initial.getLostRegisterId()
        ) || !Objects.equals(
                claim.getClaimantUserId(),
                initial.getClaimantUserId()
        )) {
            throw new BusinessException(
                    409,
                    "申请关联信息发生变化，请刷新后重试"
            );
        }

        if (registration == null) {
            throw new BusinessException(
                    409,
                    "关联的丢失登记不存在或归属不一致"
            );
        }

        // 比较双方身份，不能拿当前操作人和申请人直接比较。
        if (Objects.equals(
                property.getUserId(),
                claim.getClaimantUserId()
        )) {
            throw new BusinessException(
                    409,
                    "拾到者与申请人不能是同一个用户"
            );
        }

        return new ClaimReviewContext(
                property,
                registration,
                claim
        );
    }
    @Transactional(rollbackFor = Exception.class)
    public ClaimSubmissionVO reject(
            Long userId,
            Long claimId,
            RejectClaimRequest request
    ) {
        if (request == null
                || request.rejectReason() == null
                || request.rejectReason().isBlank()) {
            throw new BusinessException(
                    400,
                    "请填写拒绝原因"
            );
        }

        if (request.rejectReason().length() > 255) {
            throw new BusinessException(
                    400,
                    "拒绝原因不能超过255个字符"
            );
        }

        ClaimReviewContext context =
                lockForReview(userId, claimId);

        ItemClaimEntity claim = context.claim();

        // 重复拒绝返回原申请，不覆盖第一次的拒绝原因。
        if ("REJECTED".equals(claim.getStatus())) {
            return ClaimSubmissionVO.from(claim);
        }

        if (!"PENDING".equals(claim.getStatus())) {
            throw new BusinessException(
                    409,
                    "只有待审核申请才能拒绝"
            );
        }

        String reason = request.rejectReason().strip();

        int rows = itemClaimMapper.rejectPending(
                claim.getId(),
                reason
        );

        if (rows != 1) {
            throw new BusinessException(
                    409,
                    "申请已被处理，请刷新后重试"
            );
        }

        claim.setStatus("REJECTED");
        claim.setRejectReason(reason);

        return ClaimSubmissionVO.from(claim);
    }
    /**
     * 拾到者确认已经交接物品。
     */
    @Transactional(rollbackFor = Exception.class)
    public ClaimSubmissionVO handover(
            Long userId,
            Long claimId
    ) {
        ClaimReviewContext context = lockForClaim(
                userId,
                claimId,
                ClaimActor.FINDER
        );

        LostPropertyEntity property = context.property();
        LostRegisterEntity registration = context.registration();
        ItemClaimEntity claim = context.claim();

        /*
         * 重复提交返回当前状态。
         * 不重复写入交接时间。
         */
        if ("HANDOVER_PENDING".equals(claim.getStatus())
                || "COMPLETED".equals(claim.getStatus())) {
            return ClaimSubmissionVO.from(claim);
        }

        if (!"APPROVED".equals(claim.getStatus())) {
            throw new BusinessException(
                    409,
                    "只有已同意的申请才能确认交接"
            );
        }

        if (!"RESERVED".equals(property.getStatus())
                || !"IN_HANDOVER".equals(registration.getStatus())) {
            throw new BusinessException(
                    409,
                    "物品状态与认领申请不一致，无法确认交接"
            );
        }

        int rows = itemClaimMapper.markHandoverPending(
                claim.getId()
        );

        if (rows != 1) {
            throw new BusinessException(
                    409,
                    "申请状态发生变化，请刷新后重试"
            );
        }

        claim.setStatus("HANDOVER_PENDING");

        return ClaimSubmissionVO.from(claim);
    }
    /**
     * 申请人确认收到物品，完成归还。
     */
    @Transactional(rollbackFor = Exception.class)
    public ClaimSubmissionVO receive(
            Long userId,
            Long claimId
    ) {
        ClaimReviewContext context = lockForClaim(
                userId,
                claimId,
                ClaimActor.CLAIMANT
        );

        LostPropertyEntity property = context.property();
        LostRegisterEntity registration = context.registration();
        ItemClaimEntity claim = context.claim();

        /*
         * 已完成的申请重复确认时直接返回。
         * 不重复增加版本号，也不覆盖完成时间。
         */
        if ("COMPLETED".equals(claim.getStatus())) {
            return ClaimSubmissionVO.from(claim);
        }

        if (!"HANDOVER_PENDING".equals(claim.getStatus())) {
            throw new BusinessException(
                    409,
                    "请等待拾到者确认交接后，再确认收到物品"
            );
        }

        if (!"RESERVED".equals(property.getStatus())
                || !"IN_HANDOVER".equals(registration.getStatus())) {
            throw new BusinessException(
                    409,
                    "物品状态与认领申请不一致，无法完成归还"
            );
        }

        // 1. 拾物登记标记为已归还。
        int propertyRows = lostPropertyMapper.markReturned(
                property.getId(),
                property.getUserId(),
                property.getVersion()
        );

        if (propertyRows != 1) {
            throw new BusinessException(
                    409,
                    "拾物登记状态发生变化，请刷新后重试"
            );
        }

        // 2. 申请人的丢失登记标记为已找回。
        int registrationRows = lostRegisterMapper.markRecovered(
                registration.getId(),
                claim.getClaimantUserId(),
                registration.getVersion()
        );

        if (registrationRows != 1) {
            throw new BusinessException(
                    409,
                    "丢失登记状态发生变化，请刷新后重试"
            );
        }

        // 3. 申请标记为已完成。
        int claimRows = itemClaimMapper.completeHandover(
                claim.getId(),
                userId
        );

        if (claimRows != 1) {
            throw new BusinessException(
                    409,
                    "申请状态发生变化，请刷新后重试"
            );
        }

        /*
         * 提交成功后再次同步，确保已归还物品不在可匹配索引中。
         * 如果此前审核时的同步失败，这里也能补一次同步。
         */
        lostPropertyIndexService.syncAfterCommit(
                property.getId()
        );

        claim.setStatus("COMPLETED");

        return ClaimSubmissionVO.from(claim);
    }
    /**
     * 申请人取消尚未交接的认领申请。
     */
    @Transactional(rollbackFor = Exception.class)
    public ClaimSubmissionVO cancel(
            Long userId,
            Long claimId
    ) {
        ClaimReviewContext context = lockForClaim(
                userId,
                claimId,
                ClaimActor.CLAIMANT
        );

        LostPropertyEntity property = context.property();
        LostRegisterEntity registration = context.registration();
        ItemClaimEntity claim = context.claim();

        // 重复取消不再修改任何物品状态。
        if ("CANCELLED".equals(claim.getStatus())) {
            return ClaimSubmissionVO.from(claim);
        }

        /*
         * 待审核申请没有占用物品，
         * 因此只取消申请。
         */
        if ("PENDING".equals(claim.getStatus())) {
            int rows = itemClaimMapper.cancelPending(
                    claim.getId()
            );

            if (rows != 1) {
                throw new BusinessException(
                        409,
                        "申请状态发生变化，请刷新后重试"
                );
            }

            claim.setStatus("CANCELLED");

            return ClaimSubmissionVO.from(claim);
        }

        if (!"APPROVED".equals(claim.getStatus())) {
            throw new BusinessException(
                    409,
                    "只有待审核或已同意且尚未交接的申请才能取消"
            );
        }

        if (!"RESERVED".equals(property.getStatus())
                || !"IN_HANDOVER".equals(registration.getStatus())) {
            throw new BusinessException(
                    409,
                    "物品状态与申请不一致，无法取消"
            );
        }

        // 1. 释放拾得物品。
        int propertyRows = lostPropertyMapper.releaseReservation(
                property.getId(),
                property.getUserId(),
                property.getVersion()
        );

        if (propertyRows != 1) {
            throw new BusinessException(
                    409,
                    "拾物登记状态发生变化，请刷新后重试"
            );
        }

        // 2. 恢复申请人的寻找状态。
        int registrationRows = lostRegisterMapper.resumeSearching(
                registration.getId(),
                claim.getClaimantUserId(),
                registration.getVersion()
        );

        if (registrationRows != 1) {
            throw new BusinessException(
                    409,
                    "丢失登记状态发生变化，请刷新后重试"
            );
        }

        // 3. 取消申请。
        int claimRows = itemClaimMapper.cancelApproved(
                claim.getId()
        );

        if (claimRows != 1) {
            throw new BusinessException(
                    409,
                    "申请状态发生变化，请刷新后重试"
            );
        }

        /*
         * 拾物恢复 AVAILABLE，
         * 事务提交成功后重新加入匹配索引。
         */
        lostPropertyIndexService.syncAfterCommit(
                property.getId()
        );

        claim.setStatus("CANCELLED");

        return ClaimSubmissionVO.from(claim);
    }
    /**
     * 查询申请详情。
     */
    public ClaimDetailVO detail(
            Long userId,
            Long claimId
    ) {
        validateClaimReadRequest(userId, claimId);

        ClaimDetailVO detail =
                itemClaimMapper.findDetailForParticipant(
                        claimId,
                        userId
                );

        if (detail == null) {
            throw new BusinessException(
                    404,
                    "认领申请不存在或无权查看"
            );
        }

        return detail;
    }

    /**
     * 查询对方联系方式。
     */
    public ClaimContactVO contact(
            Long userId,
            Long claimId
    ) {
        validateClaimReadRequest(userId, claimId);

        ClaimContactVO contact =
                itemClaimMapper.findContactForParticipant(
                        claimId,
                        userId
                );

        if (contact == null) {
            throw new BusinessException(
                    404,
                    "认领申请不存在或无权查看"
            );
        }

        String status = contact.getStatus();

        boolean allowed =
                "APPROVED".equals(status)
                        || "HANDOVER_PENDING".equals(status)
                        || "COMPLETED".equals(status);

        if (!allowed) {
            throw new BusinessException(
                    403,
                    "当前申请状态不允许查看对方联系方式"
            );
        }

        if (contact.getRealName() == null
                || contact.getRealName().isBlank()
                || contact.getPhone() == null
                || contact.getPhone().isBlank()) {
            throw new BusinessException(
                    409,
                    "对方当前联系资料不完整，暂时无法获取联系方式"
            );
        }

        contact.setRealName(
                contact.getRealName().strip()
        );
        contact.setPhone(
                contact.getPhone().strip()
        );

        return contact;
    }

    private void validateClaimReadRequest(
            Long userId,
            Long claimId
    ) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录");
        }

        if (claimId == null || claimId <= 0) {
            throw new BusinessException(
                    400,
                    "认领申请编号不正确"
            );
        }
    }
}