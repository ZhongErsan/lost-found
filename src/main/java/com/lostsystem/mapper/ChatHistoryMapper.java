package com.lostsystem.mapper;

import com.lostsystem.entity.ChatHistoryEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatHistoryMapper extends BaseMapperX<ChatHistoryEntity>{


    @Select("""
        SELECT id,
               created_date,
               updated_date,
               session_id,
               role,
               content
        FROM chat_history
        WHERE session_id = #{sessionId}
          AND id < (
              SELECT COALESCE(MAX(id), 0)
              FROM chat_history
              WHERE session_id = #{sessionId}
                AND role = '0'
          )
        ORDER BY id DESC
        LIMIT 20
        """)
    List<ChatHistoryEntity> findTop20BySessionIdOrderByIdDesc(
            @Param("sessionId") String sessionId
    );
}
