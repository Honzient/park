package com.parking.service;

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

import java.util.List;

public interface AdminService {

    PageResult<AdminUserVO> users(UserPageQueryDTO queryDTO);

    List<AdminRoleVO> roles();

    List<PermissionNodeVO> permissionTree();

    void assignRole(BatchRoleAssignDTO assignDTO, String operator, String requestUri, String ip, String device);

    void updateUser(AdminUserUpdateDTO updateDTO, String operator, String requestUri, String ip, String device);

    void updateRolePermissions(RolePermissionUpdateDTO updateDTO, String operator, String requestUri, String ip, String device);

    PageResult<OperationLogVO> operationLogs(LogPageQueryDTO queryDTO);

    PageResult<LoginLogVO> loginLogs(LogPageQueryDTO queryDTO);
}