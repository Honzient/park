package com.parking.domain.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class RolePermissionUpdateDTO {

    @NotBlank
    private String roleCode;

    private List<String> permissions;
}
