package com.parking.controller;

import com.parking.common.ApiResponse;
import com.parking.common.PageResult;
import com.parking.domain.dto.admin.AdminUserUpdateDTO;
import com.parking.domain.dto.admin.BatchRoleAssignDTO;
import com.parking.domain.dto.admin.LogPageQueryDTO;
import com.parking.domain.dto.admin.RolePermissionUpdateDTO;
import com.parking.domain.dto.admin.UserPageQueryDTO;
import com.parking.domain.vo.admin.AdminRoleVO;
import com.parking.domain.vo.admin.AdminUserVO;
import com.parking.domain.vo.admin.LoginLogVO;
import com.parking.domain.vo.admin.OperationLogVO;
import com.parking.domain.vo.admin.PermissionNodeVO;
import com.parking.security.SecurityUtils;
import com.parking.service.AdminService;
import com.parking.util.RequestInfoUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/users/page")
    @PreAuthorize("hasAuthority('admin:user:view')")
    public ApiResponse<PageResult<AdminUserVO>> users(@RequestBody UserPageQueryDTO queryDTO) {
        return ApiResponse.success(adminService.users(queryDTO));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('admin:role:view')")
    public ApiResponse<List<AdminRoleVO>> roles() {
        return ApiResponse.success(adminService.roles());
    }

    @GetMapping("/permissions/tree")
    @PreAuthorize("hasAuthority('admin:role:view')")
    public ApiResponse<List<PermissionNodeVO>> permissionTree() {
        return ApiResponse.success(adminService.permissionTree());
    }

    @PostMapping("/users/assign-role")
    @PreAuthorize("hasAuthority('admin:user:assign-role')")
    public ApiResponse<Void> assignRole(@Valid @RequestBody BatchRoleAssignDTO assignDTO, HttpServletRequest request) {
        adminService.assignRole(
                assignDTO,
                SecurityUtils.getCurrentUsername().orElse("unknown"),
                RequestInfoUtils.uri(request),
                RequestInfoUtils.clientIp(request),
                RequestInfoUtils.device(request)
        );
        return ApiResponse.success("Role assigned", null);
    }

    @PostMapping("/users/update")
    @PreAuthorize("hasAuthority('admin:user:assign-role')")
    public ApiResponse<Void> updateUser(@Valid @RequestBody AdminUserUpdateDTO updateDTO, HttpServletRequest request) {
        adminService.updateUser(
                updateDTO,
                SecurityUtils.getCurrentUsername().orElse("unknown"),
                RequestInfoUtils.uri(request),
                RequestInfoUtils.clientIp(request),
                RequestInfoUtils.device(request)
        );
        return ApiResponse.success("User updated", null);
    }

    @PostMapping("/roles/permissions")
    @PreAuthorize("hasAuthority('admin:role:edit')")
    public ApiResponse<Void> updateRolePermissions(@Valid @RequestBody RolePermissionUpdateDTO updateDTO, HttpServletRequest request) {
        adminService.updateRolePermissions(
                updateDTO,
                SecurityUtils.getCurrentUsername().orElse("unknown"),
                RequestInfoUtils.uri(request),
                RequestInfoUtils.clientIp(request),
                RequestInfoUtils.device(request)
        );
        return ApiResponse.success("Role permissions updated", null);
    }

    @PostMapping("/logs/operation")
    @PreAuthorize("hasAuthority('admin:log:view')")
    public ApiResponse<PageResult<OperationLogVO>> operationLogs(@RequestBody LogPageQueryDTO queryDTO) {
        return ApiResponse.success(adminService.operationLogs(queryDTO));
    }

    @PostMapping("/logs/login")
    @PreAuthorize("hasAuthority('admin:log:view')")
    public ApiResponse<PageResult<LoginLogVO>> loginLogs(@RequestBody LogPageQueryDTO queryDTO) {
        return ApiResponse.success(adminService.loginLogs(queryDTO));
    }
}