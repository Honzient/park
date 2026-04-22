import { request } from './http';
import type { CaptchaData, CurrentUserInfo, LoginData, LoginPayload } from '@/types/auth';

export const fetchCaptcha = (): Promise<CaptchaData> => {
  return request<CaptchaData>({
    url: '/auth/captcha',
    method: 'GET',
  });
};

export const login = (payload: LoginPayload): Promise<LoginData> => {
  return request<LoginData>({
    url: '/auth/login',
    method: 'POST',
    data: payload,
  });
};

export const fetchCurrentUser = (): Promise<CurrentUserInfo> => {
  return request<CurrentUserInfo>({
    url: '/auth/me',
    method: 'GET',
  });
};
