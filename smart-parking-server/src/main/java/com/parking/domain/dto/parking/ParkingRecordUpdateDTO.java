package com.parking.domain.dto.parking;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ParkingRecordUpdateDTO {

    @NotBlank
    @Size(max = 20)
    private String plateNumber;

    @NotBlank
    @Size(max = 20)
    private String parkNo;

    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime entryTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime exitTime;

    @NotNull
    @DecimalMin(value = "0.00")
    private BigDecimal fee;

    @NotBlank
    @Size(max = 20)
    private String status;
}