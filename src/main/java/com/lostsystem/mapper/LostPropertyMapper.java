package com.lostsystem.mapper;

import com.lostsystem.entity.LostPropertyEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface LostPropertyMapper extends BaseMapperX<LostPropertyEntity>{
    @Select("""
    SELECT id,
           lost_name,
           lost_type
    FROM lost_property
    WHERE status = 'AVAILABLE'
    ORDER BY id
    """)
    List<LostPropertyEntity> findAllForIndex();
    @Select("""
    SELECT id, lost_name, lost_type
    FROM lost_property
    WHERE id = #{id}
      AND status = 'AVAILABLE'
    """)
    LostPropertyEntity findByIdForIndex(@Param("id") Long id);

    /**
     * 查询全部拾物登记记录，按 id 倒序排列。
     */
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
                       returned_at
                FROM lost_property
            ORDER BY id DESC
            """)
    List<LostPropertyEntity> findAll();

    /**
     * 查询该用户登记的拾得失物信息
     */
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
                   returned_at
            FROM lost_property
        WHERE user_id = #{userId}
          AND phone = #{phone}
        ORDER BY id DESC
        """)
    List<LostPropertyEntity> findAllByUserIdAndPhone(
            @Param("userId") Long userId,
            @Param("phone") String phone
    );
    /**
     * 查询当前用户最近20条拾物登记。
     */
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
                   returned_at
            FROM lost_property
        WHERE user_id = #{userId}
        ORDER BY id DESC
        LIMIT 20
        """)
    List<LostPropertyEntity> findTop20ByUserIdOrderByIdDesc(
            @Param("userId") Long userId
    );
    @Update("""
    UPDATE lost_property
    SET lost_name = #{lostName},
        lost_type = #{lostType},
        version = version + 1,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND status = 'AVAILABLE'
      AND version = #{version}
    """)
    int updateItemInformation(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("version") Long version,
            @Param("lostName") String lostName,
            @Param("lostType") String lostType
    );
    /**
     * 认领业务内部使用：查询并锁定拾物登记。
     * 必须在事务中调用。
     */
    @Select("""
    SELECT id,
           user_id,
           status,
           version
    FROM lost_property
    WHERE id = #{id}
    FOR UPDATE
    """)
    LostPropertyEntity findByIdForUpdate(
            @Param("id") Long id
    );
    /**
     * 审核同意后，将拾得物品切换为已预留。
     */
    @Update("""
    UPDATE lost_property
    SET status = 'RESERVED',
        version = version + 1,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND status = 'AVAILABLE'
      AND version = #{version}
    """)
    int reserveForClaim(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("version") Long version
    );
    /**
     * 申请人确认收到后，将拾物登记标记为已归还。
     */
    @Update("""
    UPDATE lost_property
    SET status = 'RETURNED',
        returned_at = CURRENT_TIMESTAMP,
        version = version + 1,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND status = 'RESERVED'
      AND version = #{version}
    """)
    int markReturned(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("version") Long version
    );
    /**
     * 获批申请在交接前取消，释放预留的拾得物品。
     */
    @Update("""
    UPDATE lost_property
    SET status = 'AVAILABLE',
        version = version + 1,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND status = 'RESERVED'
      AND version = #{version}
    """)
    int releaseReservation(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("version") Long version
    );
    @Update("""
    UPDATE lost_property
    SET image_url = #{imageUrl},
        version = version + 1,
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND status = 'AVAILABLE'
      AND version = #{version}
    """)
    int updateImage(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("version") Long version,
            @Param("imageUrl") String imageUrl
    );

}
