package com.lostsystem.mapper;

import com.lostsystem.entity.ChatConversationEntity;
import com.lostsystem.vo.ChatConversationListItemVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ChatConversationMapper extends BaseMapperX<ChatConversationEntity> {
    /**
     * 查询当前用户的聊天会话。
     * 按会话创建编号倒序排列。
     */
    @Select("""
        <script>
        SELECT c.id,
               c.session_id,
               c.mode,
               c.created_date,
               COALESCE(
                   (
                       SELECT LEFT(h.content, 40)
                       FROM chat_history h
                       WHERE h.session_id = c.session_id
                         AND h.role = '0'
                       ORDER BY h.id ASC
                       LIMIT 1
                   ),
                   '新对话'
               ) AS title,
               (
                   SELECT h.created_date
                   FROM chat_history h
                   WHERE h.session_id = c.session_id
                   ORDER BY h.id DESC
                   LIMIT 1
               ) AS last_message_at
        FROM chat_conversation c
        WHERE c.user_id = #{userId}
        <if test="beforeId != null">
            AND c.id &lt; #{beforeId}
        </if>
        ORDER BY c.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ChatConversationListItemVO> findByUserId(
            @Param("userId") Long userId,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit
    );
    /**
     * 查询并锁定当前用户自己的会话。
     * 必须在事务中调用。
     */
    @Select("""
    SELECT id,
           session_id,
           user_id,
           mode
    FROM chat_conversation
    WHERE session_id = #{sessionId}
      AND user_id = #{userId}
    FOR UPDATE
    """)
    ChatConversationEntity findOwnedForUpdate(
            @Param("sessionId") String sessionId,
            @Param("userId") Long userId
    );

    /**
     * 仅允许为模式未知的会话补录模式。
     */
    @Update("""
    UPDATE chat_conversation
    SET mode = #{mode},
        updated_date = CURRENT_TIMESTAMP
    WHERE id = #{id}
      AND user_id = #{userId}
      AND mode IS NULL
    """)
    int fillMissingMode(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("mode") String mode
    );
}