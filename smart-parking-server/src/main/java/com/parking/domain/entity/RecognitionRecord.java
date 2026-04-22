package com.parking.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("recognition_record")
public class RecognitionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String plateNumber;

    private LocalDateTime recognitionTime;

    private BigDecimal accuracy;

    private String recognitionType;

    private String sourceUrl;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
