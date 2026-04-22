package com.parking.controller;

import com.parking.common.ApiResponse;
import com.parking.domain.dto.auth.LoginRequest;
import com.parking.domain.vo.auth.CaptchaVO;
import com.parking.domain.vo.auth.LoginVO;
import com.parking.repository.InMemoryIdentityStore;
import com.parking.security.SecurityUtils;
import com.parking.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final InMemoryIdentityStore identityStore;

    public AuthController(AuthService authService, InMemoryIdentityStore identityStore) {
        this.authService = authService;
        this.identityStore = identityStore;
    }

    @GetMapping("/captcha")
    public ApiResponse<CaptchaVO> captcha() {
        return ApiResponse.success(authService.generateCaptcha());
    }

    @PostMapping("/login")
    public ApiResponse<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> me() {
        String username = SecurityUtils.getCurrentUsername().orElse("unknown");

        InMemoryIdentityStore.UserAccount user = null;
        try {
            user = identityStore.getByUsername(username);
        } catch (Exception ignored) {
            // If identity tables are not available yet, fallback to JWT-authenticated info.
        }

        if (user == null) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            var authorities = authentication == null
                    ? java.util.List.<String>of()
                    : authentication.getAuthorities().stream().map(item -> item.getAuthority()).toList();
            return ApiResponse.success(Map.of("username", username, "permissions", authorities));
        }

        return ApiResponse.success(Map.of(
                "username", user.username(),
                "realName", user.realName(),
                "phone", user.phone(),
                "roleCode", user.roleCode(),
                "permissions", identityStore.getPermissions(user.roleCode())
        ));
    }
}
