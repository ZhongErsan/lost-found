package com.lostsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lostsystem.entity.BaseEntity;

public interface BaseMapperX<T extends BaseEntity>
        extends BaseMapper<T> {
}