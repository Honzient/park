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
@TableName("parking_record")
public class ParkingRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String plateNumber;

    private String parkNo;

    private LocalDateTime entryTime;

    private LocalDateTime exitTime;

    private Integer durationMinutes;

    private BigDecimal fee;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
