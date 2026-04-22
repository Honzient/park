package com.parking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.parking.domain.entity.ParkingSpot;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ParkingSpotMapper extends BaseMapper<ParkingSpot> {
}
