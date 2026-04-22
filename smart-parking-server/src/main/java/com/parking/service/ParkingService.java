package com.parking.service;

import com.parking.common.PageResult;
import com.parking.domain.dto.parking.ParkingRecordQueryDTO;
import com.parking.domain.dto.parking.ParkingRecordUpdateDTO;
import com.parking.domain.dto.parking.SpotAssignDTO;
import com.parking.domain.dto.parking.SpotStatusUpdateDTO;
import com.parking.domain.vo.parking.AssignmentVehicleVO;
import com.parking.domain.vo.parking.ParkingRecordVO;
import com.parking.domain.vo.parking.ParkingSpotVO;

import java.util.List;

public interface ParkingService {

    PageResult<ParkingRecordVO> queryRecords(ParkingRecordQueryDTO queryDTO);

    ParkingRecordVO getDetail(Long id);

    void updateRecord(Long id, ParkingRecordUpdateDTO updateDTO);

    List<ParkingSpotVO> listSpots();

    List<AssignmentVehicleVO> listAssignmentVehicles();

    void assignSpot(SpotAssignDTO assignDTO);

    void updateSpotStatus(SpotStatusUpdateDTO updateDTO);
}