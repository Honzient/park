package com.parking.domain.dto.parking;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SpotStatusUpdateDTO {

    @NotBlank
    private String spotNo;

    @NotBlank
    private String targetStatus;

    private String plateNumber;
}