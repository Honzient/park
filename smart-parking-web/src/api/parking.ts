import { request } from './http';
import type { PageResult } from '@/types/common';
import type {
  AssignmentVehicle,
  ParkingQueryPayload,
  ParkingRecord,
  ParkingRecordUpdatePayload,
  ParkingSpot,
  SpotAssignPayload,
  SpotStatusUpdatePayload,
} from '@/types/parking';

export const queryParkingRecords = (payload: ParkingQueryPayload): Promise<PageResult<ParkingRecord>> => {
  return request<PageResult<ParkingRecord>>({
    url: '/parking/records/query',
    method: 'POST',
    data: payload,
  });
};

export const fetchParkingDetail = (id: number): Promise<ParkingRecord> => {
  return request<ParkingRecord>({
    url: `/parking/records/${id}`,
    method: 'GET',
  });
};

export const updateParkingRecord = async (id: number, payload: ParkingRecordUpdatePayload): Promise<void> => {
  const url = `/parking/records/${id}`;
  try {
    return await request<void>({
      url,
      method: 'PUT',
      data: payload,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error ?? '');
    if (message.includes("Request method 'PUT' is not supported")) {
      return request<void>({
        url,
        method: 'POST',
        data: payload,
      });
    }
    throw error;
  }
};

export const fetchParkingSpots = (): Promise<ParkingSpot[]> => {
  return request<ParkingSpot[]>({
    url: '/parking/spots',
    method: 'GET',
  });
};

export const fetchAssignmentVehicles = (): Promise<AssignmentVehicle[]> => {
  return request<AssignmentVehicle[]>({
    url: '/parking/spots/vehicles',
    method: 'GET',
  });
};

export const assignParkingSpot = (payload: SpotAssignPayload): Promise<void> => {
  return request<void>({
    url: '/parking/spots/assign',
    method: 'POST',
    data: payload,
  });
};

export const updateParkingSpotStatus = (payload: SpotStatusUpdatePayload): Promise<void> => {
  return request<void>({
    url: '/parking/spots/status',
    method: 'POST',
    data: payload,
  });
};
