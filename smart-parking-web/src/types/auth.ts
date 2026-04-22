export interface CaptchaData {
  captchaId: string;
  imageBase64: string;
}

export interface LoginPayload {
  username: string;
  password: string;
  captchaId: string;
  captchaCode: string;
}

export interface LoginData {
  token: string;
  tokenType: string;
  expireAt: number;
  username: string;
  permissions: string[];
}

export interface CurrentUserInfo {
  username: string;
  realName?: string;
  phone?: string;
  roleCode?: string;
  permissions: string[];
}
