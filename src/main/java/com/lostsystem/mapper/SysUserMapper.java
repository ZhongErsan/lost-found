package com.lostsystem.mapper;

import com.lostsystem.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysUserMapper extends BaseMapperX<SysUser> {
    @Select("""
            SELECT id,
                   username,
                   password,
                   real_name,
                   phone,
                   role,
                   status,
                   created_date,
                   updated_date
            FROM sys_user
            WHERE username = #{username}
            LIMIT 1
            """)
    SysUser findByUsername(@Param("username") String username);
}