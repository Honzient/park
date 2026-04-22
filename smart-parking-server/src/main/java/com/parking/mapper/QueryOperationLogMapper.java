package com.parking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.parking.domain.entity.QueryOperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QueryOperationLogMapper extends BaseMapper<QueryOperationLog> {
}
