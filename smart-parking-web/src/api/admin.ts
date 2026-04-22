import { request } from './http';
import type { PageResult } from '@/types/common';
import type {
  AdminRole,
  AdminUser,
  AdminUserUpdatePayload,
  LoginLog,
  OperationLog,
  PermissionNode,
} from '@/types/admin';

export const queryAdminUsers = (payload: { pageNo: number; pageSize: number; keyword?: string }): Promise<PageResult<AdminUser>> => {
  return request<PageResult<AdminUser>>({
    url: '/admin/users/page',
    method: 'POST',
    data: payload,
  });
};

export const assignRoleBatch = (payload: { userIds: number[]; roleCode: string }): Promise<void> => {
  return request<void>({
    url: '/admin/users/assign-role',
    method: 'POST',
    data: payload,
  });
};

export const updateAdminUser = (payload: AdminUserUpdatePayload): Promise<void> => {
  return request<void>({
    url: '/admin/users/update',
    method: 'POST',
    data: payload,
  });
};

export const fetchAdminRoles = (): Promise<AdminRole[]> => {
  return request<AdminRole[]>({
    url: '/admin/roles',
    method: 'GET',
  });
};

export const fetchPermissionTree = (): Promise<PermissionNode[]> => {
  return request<PermissionNode[]>({
    url: '/admin/permissions/tree',
    method: 'GET',
  });
};

export const updateRolePermissions = (payload: { roleCode: string; permissions: string[] }): Promise<void> => {
  return request<void>({
    url: '/admin/roles/permissions',
    method: 'POST',
    data: payload,
  });
};

export const queryOperationLogs = (payload: { pageNo: number; pageSize: number; keyword?: string }): Promise<PageResult<OperationLog>> => {
  return request<PageResult<OperationLog>>({
    url: '/admin/logs/operation',
    method: 'POST',
    data: payload,
  });
};

export const queryLoginLogs = (payload: { pageNo: number; pageSize: number; keyword?: string }): Promise<PageResult<LoginLog>> => {
  return request<PageResult<LoginLog>>({
    url: '/admin/logs/login',
    method: 'POST',
    data: payload,
  });
};