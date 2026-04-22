package com.parking.domain.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$", message = "Password must contain upper/lowercase letters and numbers, at least 8 chars")
    private String password;

    @NotBlank
    private String captchaId;

    @NotBlank
    private String captchaCode;
}
