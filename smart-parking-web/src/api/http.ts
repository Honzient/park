import axios, { type AxiosRequestConfig } from 'axios';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/store/auth';
import { pinia } from '@/store';
import type { ApiResponse } from '@/types/common';

const TOKEN_KEY = 'SMART_PARKING_TOKEN';
const BACKEND_NOTICE_INTERVAL_MS = 8000;
const NO_FREE_SPOT_MESSAGE = '暂无空闲车位';

let lastBackendNoticeAt = 0;
let noFreeSpotNoticeActive = false;

const safeReadToken = (): string => {
  try {
    return window.localStorage.getItem(TOKEN_KEY) || '';
  } catch {
    return '';
  }
};

const safeDataToText = (data: unknown): string => {
  if (typeof data === 'string') {
    return data;
  }
  if (data instanceof ArrayBuffer) {
    try {
      return new TextDecoder().decode(new Uint8Array(data));
    } catch {
      return '';
    }
  }
  if (data && typeof data === 'object') {
    const message = (data as { message?: unknown }).message;
    if (typeof message === 'string') {
      return message;
    }
    try {
      return JSON.stringify(data);
    } catch {
      return '';
    }
  }
  return '';
};

const showBackendUnavailable = (): void => {
  const now = Date.now();
  if (now - lastBackendNoticeAt < BACKEND_NOTICE_INTERVAL_MS) {
    return;
  }

  lastBackendNoticeAt = now;
  const apiTarget = (import.meta.env.VITE_PROXY_API_TARGET || 'http://127.0.0.1:8080').trim();
  ElMessage.error(`Cannot reach backend (${apiTarget}). Start smart-parking-server or adjust VITE_PROXY_API_TARGET.`);
};

const isNoFreeSpotMessage = (message: string): boolean => {
  return message.includes(NO_FREE_SPOT_MESSAGE);
};

const showNoFreeSpotNotice = (): void => {
  if (noFreeSpotNoticeActive) {
    return;
  }

  noFreeSpotNoticeActive = true;
  ElMessageBox.alert(NO_FREE_SPOT_MESSAGE, '提示', {
    confirmButtonText: '知道了',
    type: 'warning',
    showClose: true,
    closeOnClickModal: false,
    closeOnPressEscape: true,
  })
    .catch(() => undefined)
    .finally(() => {
      noFreeSpotNoticeActive = false;
    });
};

const isBackendUnavailable = (error: unknown): boolean => {
  const axiosError = error as {
    message?: string;
    code?: string;
    response?: {
      status?: number;
      data?: unknown;
    };
  };

  const message = (axiosError.message || '').toLowerCase();
  const code = (axiosError.code || '').toLowerCase();
  const status = axiosError.response?.status;
  const dataText = safeDataToText(axiosError.response?.data).toLowerCase();
  const mixedText = `${message} ${code} ${dataText}`;

  if (!status) {
    return true;
  }

  if (
    mixedText.includes('proxy error') ||
    mixedText.includes('error occurred while trying to proxy') ||
    mixedText.includes('econnrefused') ||
    mixedText.includes('connect econnrefused') ||
    mixedText.includes('aggregateerror') ||
    mixedText.includes('connection refused') ||
    mixedText.includes('socket hang up')
  ) {
    return true;
  }

  if ([502, 503, 504].includes(status)) {
    return true;
  }

  if (status === 500 && dataText.includes('<html')) {
    return true;
  }

  return (
    message.includes('network error') ||
    message.includes('econnrefused') ||
    message.includes('timeout') ||
    code === 'econnrefused' ||
    code === 'err_network'
  );
};

const resolveErrorMessage = (error: unknown): string => {
  const axiosError = error as {
    message?: string;
    response?: {
      data?: unknown;
    };
  };
  const data = axiosError.response?.data;
  const dataMessage = (data as { message?: unknown } | undefined)?.message;
  if (typeof dataMessage === 'string' && dataMessage.trim()) {
    return dataMessage;
  }

  const text = safeDataToText(data).trim();
  if (text && !text.toLowerCase().includes('<html')) {
    return text;
  }

  return axiosError.message || 'Network error';
};

const http = axios.create({
  baseURL: '/api',
  timeout: 5000,
});

http.interceptors.request.use((config) => {
  const token = safeReadToken() || useAuthStore(pinia).token;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResponse<unknown>;
    if (payload && typeof payload === 'object' && 'code' in payload) {
      if (payload.code !== 200) {
        const businessMessage = payload.message || 'Request failed';
        if (payload.code === 401) {
          useAuthStore(pinia).clearAuth();
          window.location.href = '/#/login';
        }
        if (isNoFreeSpotMessage(businessMessage)) {
          showNoFreeSpotNotice();
          return Promise.reject(new Error(businessMessage));
        }
        ElMessage.error(businessMessage);
        return Promise.reject(new Error(businessMessage));
      }
      return payload.data;
    }
    return response.data;
  },
  (error) => {
    const status = error?.response?.status as number | undefined;
    if (status === 401) {
      useAuthStore(pinia).clearAuth();
      window.location.href = '/#/login';
      return Promise.reject(error);
    }

    if (isBackendUnavailable(error)) {
      showBackendUnavailable();
      return Promise.reject(new Error('Backend service unavailable'));
    }

    const message = resolveErrorMessage(error);
    if (isNoFreeSpotMessage(message)) {
      showNoFreeSpotNotice();
      return Promise.reject(new Error(message));
    }
    ElMessage.error(message);
    return Promise.reject(new Error(message));
  },
);

export const request = <T>(config: AxiosRequestConfig): Promise<T> => {
  return http.request<T, T>(config);
};
