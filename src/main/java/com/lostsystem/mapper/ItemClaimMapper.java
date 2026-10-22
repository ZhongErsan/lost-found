package com.lostsystem.mapper;

import com.lostsystem.entity.ItemClaimEntity;
import com.lostsystem.vo.ClaimContactVO;
import com.lostsystem.vo.ClaimDetailVO;
import com.lostsystem.vo.ClaimListItemVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ItemClaimMapper
        extends BaseMapperX<ItemClaimEntity> {

    /**
     * 查询同一用户、同一对登记之间尚未结束的申请。
     * 使用加锁查询，读取最新已提交的数据。
     */
    @Select("""
        SELECT id,
               lost_property_id,
               lost_register_id,
               claimant_user_id,
               verification_text,
               status,
               reject_reason,
               approved_at,
               handover_at,
               completed_at,
               created_date,
               updated_date
        FROM item_claim
        WHERE lost_property_id = #{lostPropertyId}
          AND lost_register_id = #{lostRegisterId}
          AND claimant_user_id = #{claimantUserId}
          AND status IN (
              'PENDING',
              'APPROVED',
              'HANDOVER_PENDING'
          )
        ORDER BY id DESC
        LIMIT 1
        FOR UPDATE
        """)
    ItemClaimEntity findActiveForUpdate(
            @Param("lostPropertyId") Long lostPropertyId,
            @Param("lostRegisterId") Long lostRegisterId,
            @Param("claimantUserId") Long claimantUserId
    );
    /**
     * 查询当前用户提交的认领申请。
     */
    @Select("""
    <script>
    SELECT c.id,
           c.lost_property_id,
           p.lost_name AS lost_property_name,
           c.lost_register_id,
           r.lost_name AS lost_register_name,
           c.status,
           c.verification_text,
           c.reject_reason,
           c.created_date,
           c.updated_date
    FROM item_claim c
    LEFT JOIN lost_property p
           ON p.id = c.lost_property_id
    LEFT JOIN lost_register r
           ON r.id = c.lost_register_id
    WHERE c.claimant_user_id = #{userId}
    <if test="beforeId != null">
        AND c.id &lt; #{beforeId}
    </if>
    ORDER BY c.id DESC
    LIMIT #{limit}
    </script>
    """)
    List<ClaimListItemVO> findMine(
            @Param("userId") Long userId,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit
    );
    /**
     * 查询针对当前用户拾物登记的认领申请。
     */
    @Select("""
    <script>
    SELECT c.id,
           c.lost_property_id,
           p.lost_name AS lost_property_name,
           c.lost_register_id,
           r.lost_name AS lost_register_name,
           c.status,
           c.verification_text,
           c.reject_reason,
           c.created_date,
           c.updated_date
    FROM item_claim c
    INNER JOIN lost_property p
            ON p.id = c.lost_property_id
    LEFT JOIN lost_register r
           ON r.id = c.lost_register_id
    WHERE p.user_id = #{userId}
    <if test="beforeId != null">
        AND c.id &lt; #{beforeId}
    </if>
    ORDER BY c.id DESC
    LIMIT #{limit}
    </script>
    """)
    List<ClaimListItemVO> findIncoming(
            @Param("userId") Long userId,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit
    );
    /**
     * 查询并锁定指定认领申请。
     * 必须在事务中调用。
     */
    @Select("""
    SELECT id,
           lost_property_id,
           lost_register_id,
           claimant_user_id,
           verification_text,
           status,
           reject_reason,
           approved_at,
           handover_at,
           completed_at,
           created_date,
           updated_date
    FROM item_claim
    WHERE id = #{id}
    FOR UPDATE
    """)
    ItemClaimEntity findByIdForUpdate(
            @Param("id") Long id
    );

    /**
     * 仅允许把待审核申请改为已同意。
     */
    @Update("""
    UPDATE item_claim
    SET status = 'APPROVED',
        approved_at = CURRENT_TIMESTAMP,
        reject_reason = NULL,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND status = 'PENDING'
    """)
    int approvePending(
            @Param("id") Long id
    );

    /**
     * 仅允许把待审核申请改为已拒绝。
     */
    @Update("""
    UPDATE item_claim
    SET status = 'REJECTED',
        reject_reason = #{rejectReason},
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND status = 'PENDING'
    """)
    int rejectPending(
            @Param("id") Long id,
            @Param("rejectReason") String rejectReason
    );
    /**
     * 拾到者确认交接，等待申请人确认收到。
     */
    @Update("""
    UPDATE item_claim
    SET status = 'HANDOVER_PENDING',
        handover_at = CURRENT_TIMESTAMP,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND status = 'APPROVED'
    """)
    int markHandoverPending(
            @Param("id") Long id
    );

    /**
     * 申请人确认收到，完成认领。
     */
    @Update("""
    UPDATE item_claim
    SET status = 'COMPLETED',
        completed_at = CURRENT_TIMESTAMP,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND claimant_user_id = #{claimantUserId}
      AND status = 'HANDOVER_PENDING'
    """)
    int completeHandover(
            @Param("id") Long id,
            @Param("claimantUserId") Long claimantUserId
    );
    /**
     * 取消待审核申请。
     *
     * 既用于申请人主动取消，
     * 也用于归还完成后的系统收尾。
     */
    @Update("""
    UPDATE item_claim
    SET status = 'CANCELLED',
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND status = 'PENDING'
    """)
    int cancelPending(
            @Param("id") Long id
    );

    /**
     * 取消已经同意、尚未确认交接的申请。
     */
    @Update("""
    UPDATE item_claim
    SET status = 'CANCELLED',
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND status = 'APPROVED'
    """)
    int cancelApproved(
            @Param("id") Long id
    );
    /**
     * 查询因为物品已归还或失物已找回，
     * 而不再具备认领条件的待审核申请。
     */
    @Select("""
    SELECT c.id
    FROM item_claim c
    INNER JOIN lost_property p
            ON p.id = c.lost_property_id
    INNER JOIN lost_register r
            ON r.id = c.lost_register_id
    WHERE c.status = 'PENDING'
      AND (
          p.status = 'RETURNED'
          OR r.status = 'RECOVERED'
      )
    ORDER BY c.id
    LIMIT #{limit}
    """)
    List<Long> findPendingToClose(
            @Param("limit") int limit
    );
    /**
     * 只有申请人或对应拾物登记的所属用户可以查询详情。
     */
    @Select("""
    SELECT c.id,
           c.lost_property_id,
           p.lost_name AS lost_property_name,
           c.lost_register_id,
           r.lost_name AS lost_register_name,
           c.status,
           c.verification_text,
           c.reject_reason,
           CASE
               WHEN c.claimant_user_id = #{userId}
                   THEN 'CLAIMANT'
               ELSE 'FINDER'
           END AS viewer_role,
           c.approved_at,
           c.handover_at,
           c.completed_at,
           c.created_date,
           c.updated_date
    FROM item_claim c
    LEFT JOIN lost_property p
           ON p.id = c.lost_property_id
    LEFT JOIN lost_register r
           ON r.id = c.lost_register_id
    WHERE c.id = #{claimId}
      AND (
          c.claimant_user_id = #{userId}
          OR p.user_id = #{userId}
      )
    """)
    ClaimDetailVO findDetailForParticipant(
            @Param("claimId") Long claimId,
            @Param("userId") Long userId
    );
    /**
     * 查询申请对方的当前联系资料。
     *
     * 非参与者不返回记录。
     * 状态不允许时，姓名和手机号返回 NULL，
     * 由 Service 返回相应业务错误。
     */
    @Select("""
    SELECT c.id AS claim_id,
           c.status,
           CASE
               WHEN c.claimant_user_id = #{userId}
                   THEN 'FINDER'
               ELSE 'CLAIMANT'
           END AS counterparty_role,
           CASE
               WHEN c.status IN (
                   'APPROVED',
                   'HANDOVER_PENDING',
                   'COMPLETED'
               )
                   THEN u.real_name
               ELSE NULL
           END AS real_name,
           CASE
               WHEN c.status IN (
                   'APPROVED',
                   'HANDOVER_PENDING',
                   'COMPLETED'
               )
                   THEN u.phone
               ELSE NULL
           END AS phone
    FROM item_claim c
    LEFT JOIN lost_property p
           ON p.id = c.lost_property_id
    LEFT JOIN sys_user u
           ON u.id = CASE
               WHEN c.claimant_user_id = #{userId}
                   THEN p.user_id
               ELSE c.claimant_user_id
           END
    WHERE c.id = #{claimId}
      AND (
          c.claimant_user_id = #{userId}
          OR p.user_id = #{userId}
      )
    """)
    ClaimContactVO findContactForParticipant(
            @Param("claimId") Long claimId,
            @Param("userId") Long userId
    );
}