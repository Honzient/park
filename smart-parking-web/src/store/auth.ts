import { defineStore } from 'pinia';
import type { LoginData } from '@/types/auth';

const TOKEN_KEY = 'SMART_PARKING_TOKEN';
const MENU_PERMISSION_IMPLICATIONS: Record<string, string[]> = {
  'dashboard:view': ['dashboard:spot:detail'],
  'recognition:query': ['recognition:export'],
  'admin:user:view': ['admin:user:assign-role'],
  'admin:role:view': ['admin:role:edit'],
  'profile:view': ['profile:edit', 'profile:password'],
};

const storage = {
  getItem(key: string): string | null {
    try {
      return window.localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  setItem(key: string, value: string): void {
    try {
      window.localStorage.setItem(key, value);
    } catch {
      // Ignore storage errors in restricted browser modes.
    }
  },
  removeItem(key: string): void {
    try {
      window.localStorage.removeItem(key);
    } catch {
      // Ignore storage errors in restricted browser modes.
    }
  },
};

export const useAuthStore = defineStore(
  'auth',
  {
    state: () => ({
      token: storage.getItem(TOKEN_KEY) || '',
      username: '',
      roleCode: '',
      permissions: [] as string[],
      expireAt: 0,
    }),
    getters: {
      isLoggedIn: (state) => Boolean(state.token) && state.expireAt > Date.now(),
    },
    actions: {
      setAuth(payload: LoginData) {
        this.token = payload.token;
        this.username = payload.username;
        this.roleCode = '';
        this.permissions = payload.permissions;
        this.expireAt = payload.expireAt;
        storage.setItem(TOKEN_KEY, payload.token);
      },
      syncCurrentUser(payload: { username?: string; roleCode?: string; permissions?: string[] }) {
        if (typeof payload.username === 'string' && payload.username.trim()) {
          this.username = payload.username.trim();
        }
        if (typeof payload.roleCode === 'string') {
          this.roleCode = payload.roleCode.trim();
        }
        if (Array.isArray(payload.permissions)) {
          this.permissions = payload.permissions.filter((item) => typeof item === 'string');
        }
      },
      clearAuth() {
        this.token = '';
        this.username = '';
        this.roleCode = '';
        this.permissions = [];
        this.expireAt = 0;
        storage.removeItem(TOKEN_KEY);
      },
      ensureValidSession() {
        if (!this.token || !this.expireAt || this.expireAt <= Date.now()) {
          this.clearAuth();
        }
      },
      hasPermission(permission: string): boolean {
        if (this.permissions.includes(permission)) {
          return true;
        }
        return this.permissions.some((sourcePermission) => {
          const impliedPermissions = MENU_PERMISSION_IMPLICATIONS[sourcePermission];
          return Array.isArray(impliedPermissions) && impliedPermissions.includes(permission);
        });
      },
    },
    persist: {
      key: 'smart-parking-auth',
      storage,
    },
  },
);
