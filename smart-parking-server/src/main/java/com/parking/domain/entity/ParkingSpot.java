package com.parking.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("parking_spot")
public class ParkingSpot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String spotNo;

    private String status;

    private String currentPlate;

    private LocalDateTime entryTime;

    private Long recordId;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
