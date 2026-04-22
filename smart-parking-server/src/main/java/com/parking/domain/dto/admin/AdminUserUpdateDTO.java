package com.parking.domain.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminUserUpdateDTO {

    @NotNull
    private Long id;

    @NotBlank
    @Size(max = 50)
    private String username;

    @NotBlank
    @Size(max = 50)
    private String realName;

    @Size(max = 20)
    private String phone;

    @NotBlank
    @Size(max = 50)
    private String roleCode;

    @NotBlank
    @Size(max = 20)
    private String status;
}