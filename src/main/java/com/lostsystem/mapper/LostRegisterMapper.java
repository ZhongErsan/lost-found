package com.lostsystem.mapper;

import com.lostsystem.entity.LostRegisterEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface LostRegisterMapper extends BaseMapperX<LostRegisterEntity> {
    @Select("""
        SELECT id,
               created_date,
               updated_date,
               username,
               phone,
               lost_name,
               lost_type,
               status,
               version,
               recovered_at
        FROM lost_register
        WHERE user_id = #{userId}
          AND phone = #{phone}
        ORDER BY id DESC
        """)
    List<LostRegisterEntity> findAllByUserIdAndPhone(
            @Param("userId") Long userId,
            @Param("phone") String phone
    );
    @Select("""
        
            SELECT id,
               created_date,
               updated_date,
               username,
               phone,
               lost_name,
               lost_type,
               status,
               version,
               recovered_at
        FROM lost_register
        WHERE user_id = #{userId}
        ORDER BY id DESC
        LIMIT 20
        """)
    List<LostRegisterEntity> findTop20ByUserIdOrderByIdDesc(
            @Param("userId") Long userId
    );
    @Update("""
    UPDATE lost_register
    SET lost_name = #{lostName},
        lost_type = #{lostFeature},
        version = version + 1,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND status = 'SEARCHING'
      AND version = #{version}
    """)
    int updateItemInformation(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("version") Long version,
            @Param("lostName") String lostName,
            @Param("lostFeature") String lostFeature
    );
    @Select("""
    SELECT id,
           created_date,
           updated_date,
           username,
           phone,
           lost_name,
           lost_type,
           status,
           version,
           recovered_at
    FROM lost_register
    WHERE user_id = #{userId}
      AND status = 'SEARCHING'
    ORDER BY id DESC
    LIMIT 20
    """)
    List<LostRegisterEntity> findSearchingByUserIdOrderByIdDesc(
            @Param("userId") Long userId
    );
    /**
     * 查询并锁定当前用户自己的丢失登记。
     * 必须在事务中调用。
     */
    @Select("""
    SELECT id,
           user_id,
           status,
           version
    FROM lost_register
    WHERE id = #{id}
      AND user_id = #{userId}
    FOR UPDATE
    """)
    LostRegisterEntity findOwnedByIdForUpdate(
            @Param("id") Long id,
            @Param("userId") Long userId
    );
    /**
     * 审核同意后，将申请人的丢失登记切换为交接中。
     */
    @Update("""
    UPDATE lost_register
    SET status = 'IN_HANDOVER',
        version = version + 1,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND status = 'SEARCHING'
      AND version = #{version}
    """)
    int startHandover(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("version") Long version
    );
    /**
     * 申请人确认收到后，将其丢失登记标记为已找回。
     */
    @Update("""
    UPDATE lost_register
    SET status = 'RECOVERED',
        recovered_at = CURRENT_TIMESTAMP,
        version = version + 1,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND status = 'IN_HANDOVER'
      AND version = #{version}
    """)
    int markRecovered(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("version") Long version
    );
    /**
     * 获批申请在交接前取消，恢复申请人的寻找状态。
     */
    @Update("""
    UPDATE lost_register
    SET status = 'SEARCHING',
        version = version + 1,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND status = 'IN_HANDOVER'
      AND version = #{version}
    """)
    int resumeSearching(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("version") Long version
    );
    /**
     * 更新当前用户的丢失登记图片。
     */
    @Update("""
    UPDATE lost_register
    SET image_url = #{imageUrl},
        version = version + 1,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND status = 'SEARCHING'
      AND version = #{version}
    """)
    int updateImage(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("version") Long version,
            @Param("imageUrl") String imageUrl
    );
}