import { request } from './http';
import type { PageResult } from '@/types/common';
import type {
  DataCenterOverview,
  DataCenterQueryPayload,
  ParkingRecord,
} from '@/types/parking';

export const queryDataCenterRecords = (payload: DataCenterQueryPayload): Promise<PageResult<ParkingRecord>> => {
  return request<PageResult<ParkingRecord>>({
    url: '/datacenter/records/query',
    method: 'POST',
    data: payload,
  });
};

export const exportDataCenterExcel = (payload: DataCenterQueryPayload): Promise<Blob> => {
  return request<Blob>({
    url: '/datacenter/records/export/excel',
    method: 'POST',
    data: payload,
    responseType: 'blob',
  });
};

export const exportDataCenterPdf = (payload: DataCenterQueryPayload): Promise<Blob> => {
  return request<Blob>({
    url: '/datacenter/records/export/pdf',
    method: 'POST',
    data: payload,
    responseType: 'blob',
  });
};

export const fetchDataCenterOverview = (payload: DataCenterQueryPayload): Promise<DataCenterOverview> => {
  return request<DataCenterOverview>({
    url: '/datacenter/overview',
    method: 'POST',
    data: payload,
  });
};
