package com.parking.domain.dto.recognition;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.parking.domain.dto.common.PageQueryDTO;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RecognitionQueryDTO extends PageQueryDTO {

    @Size(max = 20)
    private String recognitionType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    @DecimalMin("0")
    @DecimalMax("100")
    private BigDecimal minAccuracy = new BigDecimal("90");

    @Size(max = 32)
    private String plateNumber;
}
