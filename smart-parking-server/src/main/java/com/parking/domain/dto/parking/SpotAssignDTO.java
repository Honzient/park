package com.parking.domain.dto.parking;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SpotAssignDTO {

    @NotBlank
    private String plateNumber;

    @NotBlank
    private String targetSpotNo;
}
