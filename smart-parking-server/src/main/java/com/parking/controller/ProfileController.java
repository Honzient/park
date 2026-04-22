package com.parking.controller;

import com.parking.common.ApiResponse;
import com.parking.common.PageResult;
import com.parking.domain.dto.profile.PasswordChangeDTO;
import com.parking.domain.dto.profile.ProfileUpdateDTO;
import com.parking.domain.vo.admin.LoginLogVO;
import com.parking.domain.vo.profile.ProfileVO;
import com.parking.security.SecurityUtils;
import com.parking.service.ProfileService;
import com.parking.util.RequestInfoUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('profile:view')")
    public ApiResponse<ProfileVO> me() {
        return ApiResponse.success(profileService.profile(SecurityUtils.getCurrentUsername().orElseThrow()));
    }

    @PutMapping("/me")
    @PreAuthorize("hasAuthority('profile:edit')")
    public ApiResponse<Void> updateProfile(@Valid @RequestBody ProfileUpdateDTO dto, HttpServletRequest request) {
        String username = SecurityUtils.getCurrentUsername().orElseThrow();
        profileService.updateProfile(username, dto, RequestInfoUtils.uri(request), RequestInfoUtils.clientIp(request), RequestInfoUtils.device(request));
        return ApiResponse.success("Profile updated", null);
    }

    @PutMapping("/password")
    @PreAuthorize("hasAuthority('profile:password')")
    public ApiResponse<Void> changePassword(@Valid @RequestBody PasswordChangeDTO dto, HttpServletRequest request) {
        String username = SecurityUtils.getCurrentUsername().orElseThrow();
        profileService.changePassword(username, dto, RequestInfoUtils.uri(request), RequestInfoUtils.clientIp(request), RequestInfoUtils.device(request));
        return ApiResponse.success("Password changed", null);
    }

    @GetMapping("/login-logs")
    @PreAuthorize("hasAuthority('profile:view')")
    public ApiResponse<PageResult<LoginLogVO>> loginLogs(@RequestParam(defaultValue = "1") long pageNo,
                                                          @RequestParam(defaultValue = "20") long pageSize) {
        String username = SecurityUtils.getCurrentUsername().orElseThrow();
        return ApiResponse.success(profileService.loginLogs(username, pageNo, pageSize));
    }
}
