export interface AdminUser {
  id: number;
  username: string;
  roleCode: string;
  status: string;
  lastLoginTime: string;
  realName: string;
  phone: string;
}

export interface AdminUserUpdatePayload {
  id: number;
  username: string;
  realName: string;
  phone: string;
  roleCode: string;
  status: 'ENABLED' | 'DISABLED';
}

export interface AdminRole {
  roleCode: string;
  roleName: string;
  status: string;
  permissions: string[];
}

export interface PermissionNode {
  key: string;
  label: string;
  children: PermissionNode[];
}

export interface OperationLog {
  id: number;
  operatorName: string;
  operationContent: string;
  operationTime: string;
  ip: string;
  device: string;
}

export interface LoginLog {
  id: number;
  username: string;
  loginTime: string;
  ip: string;
  device: string;
  loginStatus: string;
  message: string;
}