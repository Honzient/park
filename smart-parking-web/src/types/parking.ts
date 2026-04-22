import type { SortOrder } from './common';

export type ParkingStatus = 'FREE' | 'OCCUPIED' | 'MAINTENANCE' | 'RESERVED';
export type VehicleRecordStatus = '未出场' | '已出场';

export interface ParkingQueryState {
  plateNumber: string;
  timeRange: [string, string];
  statuses: VehicleRecordStatus[];
  parkNo: string;
  pageNo: number;
  pageSize: number;
  sortField: string;
  sortOrder: SortOrder;
}

export interface ParkingQueryPayload {
  plateNumber?: string;
  startTime?: string;
  endTime?: string;
  statuses?: VehicleRecordStatus[];
  parkNo?: string;
  pageNo: number;
  pageSize: number;
  sortField: string;
  sortOrder: SortOrder;
  advanced: boolean;
}

export interface ParkingRecord {
  id: number;
  plateNumber: string;
  parkNo: string;
  entryTime: string;
  exitTime: string | null;
  duration: string;
  fee: number;
  status: VehicleRecordStatus;
  notExited: boolean;
}

export interface ParkingRecordUpdatePayload {
  plateNumber: string;
  parkNo: string;
  entryTime: string;
  exitTime: string | null;
  fee: number;
  status: VehicleRecordStatus;
}

export interface ParkingSpot {
  spotNo: string;
  status: ParkingStatus;
  plateNumber: string | null;
  entryTime: string | null;
}

export interface AssignmentVehicle {
  recordId: number;
  plateNumber: string;
  currentSpotNo: string;
  entryTime: string;
}


export interface SpotAssignPayload {
  plateNumber: string;
  targetSpotNo: string;
}

export interface SpotStatusUpdatePayload {
  spotNo: string;
  targetStatus: ParkingStatus;
  plateNumber?: string;
}
export type DataRangePreset = 'LAST_30_DAYS' | 'TODAY' | 'THIS_WEEK' | 'THIS_MONTH' | 'CUSTOM';

export interface DataCenterQueryState extends ParkingQueryState {
  rangePreset: DataRangePreset;
}

export interface DataCenterQueryPayload extends ParkingQueryPayload {
  rangePreset: DataRangePreset;
}

export interface ProvinceTopItem {
  name: string;
  value: number;
}

export interface DataCenterTimelineItem {
  label: string;
  entryCount: number;
  exitCount: number;
}

export interface DataCenterSummary {
  recordCount: number;
  activeRecordCount: number;
  exitedRecordCount: number;
  entryEventCount: number;
  exitEventCount: number;
  totalFee: number;
  averageDurationMinutes: number;
}

export interface DataCenterOverview {
  summary: DataCenterSummary;
  timeline: DataCenterTimelineItem[];
  provinceTop5: ProvinceTopItem[];
  recordCount: number;
}

