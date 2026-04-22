package com.parking.domain.dto.datacenter;

import com.parking.domain.dto.parking.ParkingRecordQueryDTO;
import lombok.Data;

@Data
public class DataCenterRecordQueryDTO extends ParkingRecordQueryDTO {

    private String rangePreset = "LAST_30_DAYS";
}
