import { request } from './http';
import type { PageResult } from '@/types/common';
import type { LoginLog } from '@/types/admin';
import type { ProfileInfo } from '@/types/profile';

export const fetchProfile = (): Promise<ProfileInfo> => {
  return request<ProfileInfo>({
    url: '/profile/me',
    method: 'GET',
  });
};

export const updateProfile = (payload: { username: string; realName: string; phone: string }): Promise<void> => {
  return request<void>({
    url: '/profile/me',
    method: 'PUT',
    data: payload,
  });
};

export const changePassword = (payload: { oldPassword: string; newPassword: string }): Promise<void> => {
  return request<void>({
    url: '/profile/password',
    method: 'PUT',
    data: payload,
  });
};

export const queryProfileLoginLogs = (params: { pageNo: number; pageSize: number }): Promise<PageResult<LoginLog>> => {
  return request<PageResult<LoginLog>>({
    url: '/profile/login-logs',
    method: 'GET',
    params,
  });
};