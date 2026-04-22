package com.parking.domain.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class BatchRoleAssignDTO {

    private List<Long> userIds;

    @NotBlank
    private String roleCode;
}
