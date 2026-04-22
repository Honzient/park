package com.parking.domain.dto.parking;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.parking.domain.dto.common.PageQueryDTO;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ParkingRecordQueryDTO extends PageQueryDTO {

    @Size(max = 32)
    private String plateNumber;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    private List<String> statuses;

    @Size(max = 32)
    private String parkNo;
}
