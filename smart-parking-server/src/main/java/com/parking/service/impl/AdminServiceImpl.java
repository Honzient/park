package com.parking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.parking.common.PageResult;
import com.parking.common.exception.BusinessException;
import com.parking.domain.dto.admin.AdminUserUpdateDTO;
import com.parking.domain.dto.admin.BatchRoleAssignDTO;
import com.parking.domain.dto.admin.LogPageQueryDTO;
import com.parking.domain.dto.admin.RolePermissionUpdateDTO;
import com.parking.domain.dto.admin.UserPageQueryDTO;
import com.parking.domain.entity.LoginLog;
import com.parking.domain.entity.OperationLog;
import com.parking.domain.vo.admin.AdminRoleVO;
import com.parking.domain.vo.admin.AdminUserVO;
import com.parking.domain.vo.admin.LoginLogVO;
import com.parking.domain.vo.admin.OperationLogVO;
import com.parking.domain.vo.admin.PermissionNodeVO;
import com.parking.mapper.LoginLogMapper;
import com.parking.mapper.OperationLogMapper;
import com.parking.repository.InMemoryIdentityStore;
import com.parking.service.AdminService;
import com.parking.service.OperationLogService;
import com.parking.util.DateTimeUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminServiceImpl implements AdminService {

    private final InMemoryIdentityStore identityStore;
    private final OperationLogMapper operationLogMapper;
    private final LoginLogMapper loginLogMapper;
    private final OperationLogService operationLogService;

    public AdminServiceImpl(InMemoryIdentityStore identityStore,
                            OperationLogMapper operationLogMapper,
                            LoginLogMapper loginLogMapper,
                            OperationLogService operationLogService) {
        this.identityStore = identityStore;
        this.operationLogMapper = operationLogMapper;
        this.loginLogMapper = loginLogMapper;
        this.operationLogService = operationLogService;
    }

    @Override
    public PageResult<AdminUserVO> users(UserPageQueryDTO queryDTO) {
        List<AdminUserVO> users = identityStore.listUsers().stream()
                .filter(user -> {
                    String keyword = queryDTO.getKeyword();
                    if (keyword == null || keyword.isBlank()) {
                        return true;
                    }
                    return user.username().contains(keyword) || user.realName().contains(keyword);
                })
                .map(user -> new AdminUserVO(
                        user.id(),
                        user.username(),
                        user.roleCode(),
                        user.status(),
                        DateTimeUtils.format(user.lastLoginTime()),
                        user.realName(),
                        user.phone()
                ))
                .toList();

        long total = users.size();
        int from = (int) Math.min((queryDTO.getPageNo() - 1) * queryDTO.getPageSize(), total);
        int to = (int) Math.min(from + queryDTO.getPageSize(), total);
        List<AdminUserVO> pageList = users.subList(from, to);

        return new PageResult<>(pageList, total, queryDTO.getPageNo(), queryDTO.getPageSize());
    }

    @Override
    public List<AdminRoleVO> roles() {
        return identityStore.listRoles().stream()
                .map(role -> new AdminRoleVO(
                        role.roleCode(),
                        role.roleName(),
                        role.status(),
                        identityStore.getPermissions(role.roleCode())
                ))
                .toList();
    }

    @Override
    public List<PermissionNodeVO> permissionTree() {
        return List.of(
                new PermissionNodeVO("dashboard", "Dashboard", List.of(
                        new PermissionNodeVO("dashboard:view", "Dashboard View", List.of()),
                        new PermissionNodeVO("dashboard:spot:detail", "Spot Detail", List.of())
                )),
                new PermissionNodeVO("parking", "Parking", List.of(
                        new PermissionNodeVO("parking:query", "Parking Query", List.of()),
                        new PermissionNodeVO("parking:assign", "Spot Assign", List.of())
                )),
                new PermissionNodeVO("recognition", "Recognition", List.of(
                        new PermissionNodeVO("recognition:query", "Recognition Query", List.of()),
                        new PermissionNodeVO("recognition:image", "Image Recognition", List.of()),
                        new PermissionNodeVO("recognition:video", "Video Recognition", List.of()),
                        new PermissionNodeVO("recognition:export", "Recognition Export", List.of())
                )),
                new PermissionNodeVO("datacenter", "Data Center", List.of(
                        new PermissionNodeVO("datacenter:query", "Data Query", List.of()),
                        new PermissionNodeVO("datacenter:export:excel", "Export Excel", List.of()),
                        new PermissionNodeVO("datacenter:export:pdf", "Export PDF", List.of())
                )),
                new PermissionNodeVO("admin", "System Admin", List.of(
                        new PermissionNodeVO("admin:user:view", "User View", List.of()),
                        new PermissionNodeVO("admin:user:assign-role", "Assign Role", List.of()),
                        new PermissionNodeVO("admin:role:view", "Role View", List.of()),
                        new PermissionNodeVO("admin:role:edit", "Role Edit", List.of()),
                        new PermissionNodeVO("admin:log:view", "Log View", List.of())
                )),
                new PermissionNodeVO("profile", "Profile", List.of(
                        new PermissionNodeVO("profile:view", "Profile View", List.of()),
                        new PermissionNodeVO("profile:edit", "Profile Edit", List.of()),
                        new PermissionNodeVO("profile:password", "Change Password", List.of())
                ))
        );
    }

    @Override
    public void assignRole(BatchRoleAssignDTO assignDTO, String operator, String requestUri, String ip, String device) {
        if (assignDTO.getUserIds() == null || assignDTO.getUserIds().isEmpty()) {
            throw new BusinessException(400, "User list is empty");
        }
        identityStore.assignRole(assignDTO.getUserIds(), assignDTO.getRoleCode());
        operationLogService.log(operator, "Batch role assign -> " + assignDTO.getRoleCode(), requestUri, ip, device);
    }

    @Override
    public void updateUser(AdminUserUpdateDTO updateDTO, String operator, String requestUri, String ip, String device) {
        identityStore.updateUser(
                updateDTO.getId(),
                updateDTO.getUsername().trim(),
                updateDTO.getRealName().trim(),
                updateDTO.getPhone(),
                updateDTO.getRoleCode().trim(),
                updateDTO.getStatus().trim()
        );
        operationLogService.log(operator, "Update user -> " + updateDTO.getUsername(), requestUri, ip, device);
    }

    @Override
    public void updateRolePermissions(RolePermissionUpdateDTO updateDTO, String operator, String requestUri, String ip, String device) {
        List<String> permissions = updateDTO.getPermissions() == null ? List.of() : updateDTO.getPermissions();
        identityStore.updateRolePermissions(updateDTO.getRoleCode(), permissions);
        operationLogService.log(operator, "Role permissions updated -> " + updateDTO.getRoleCode(), requestUri, ip, device);
    }

    @Override
    public PageResult<OperationLogVO> operationLogs(LogPageQueryDTO queryDTO) {
        Page<OperationLog> page = new Page<>(queryDTO.getPageNo(), queryDTO.getPageSize());
        QueryWrapper<OperationLog> wrapper = new QueryWrapper<>();
        if (queryDTO.getKeyword() != null && !queryDTO.getKeyword().isBlank()) {
            wrapper.like("operation_content", queryDTO.getKeyword())
                    .or()
                    .like("operator_name", queryDTO.getKeyword());
        }
        wrapper.orderByDesc("operation_time");
        Page<OperationLog> result = operationLogMapper.selectPage(page, wrapper);

        List<OperationLogVO> records = result.getRecords().stream().map(log -> new OperationLogVO(
                log.getId(),
                log.getOperatorName(),
                log.getOperationContent(),
                DateTimeUtils.format(log.getOperationTime()),
                log.getIp(),
                log.getDevice()
        )).toList();

        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public PageResult<LoginLogVO> loginLogs(LogPageQueryDTO queryDTO) {
        Page<LoginLog> page = new Page<>(queryDTO.getPageNo(), queryDTO.getPageSize());
        QueryWrapper<LoginLog> wrapper = new QueryWrapper<>();
        if (queryDTO.getKeyword() != null && !queryDTO.getKeyword().isBlank()) {
            wrapper.like("username", queryDTO.getKeyword())
                    .or()
                    .like("ip", queryDTO.getKeyword());
        }
        wrapper.orderByDesc("login_time");
        Page<LoginLog> result = loginLogMapper.selectPage(page, wrapper);

        List<LoginLogVO> records = result.getRecords().stream().map(log -> new LoginLogVO(
                log.getId(),
                log.getUsername(),
                DateTimeUtils.format(log.getLoginTime()),
                log.getIp(),
                log.getDevice(),
                log.getLoginStatus(),
                log.getMessage()
        )).toList();

        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }
}
