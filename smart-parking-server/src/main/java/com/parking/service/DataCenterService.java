package com.parking.service;

import com.parking.common.PageResult;
import com.parking.domain.dto.datacenter.DataCenterRecordQueryDTO;
import com.parking.domain.vo.datacenter.DataCenterOverviewVO;
import com.parking.domain.vo.parking.ParkingRecordVO;

public interface DataCenterService {

    PageResult<ParkingRecordVO> queryRecords(DataCenterRecordQueryDTO queryDTO);

    byte[] exportExcel(DataCenterRecordQueryDTO queryDTO);

    byte[] exportPdf(DataCenterRecordQueryDTO queryDTO);

    DataCenterOverviewVO overview(DataCenterRecordQueryDTO queryDTO);
}
