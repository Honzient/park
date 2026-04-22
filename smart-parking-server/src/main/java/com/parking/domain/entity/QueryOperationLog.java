package com.parking.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("query_operation_log")
public class QueryOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String operatorName;

    private String module;

    private String queryConditions;

    private LocalDateTime queryTime;

    private Long costMs;

    private String requestUri;
}
