import { request } from './http';
import type { DashboardRealtime } from '@/types/dashboard';

export const fetchDashboardRealtime = (range: 'TODAY' | 'THIS_WEEK' | 'THIS_MONTH'): Promise<DashboardRealtime> => {
  return request<DashboardRealtime>({
    url: '/dashboard/realtime',
    method: 'GET',
    params: { range },
  });
};
