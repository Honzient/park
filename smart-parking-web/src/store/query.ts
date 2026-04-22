import { defineStore } from 'pinia';
import type { DataCenterQueryState, ParkingQueryState, VehicleRecordStatus } from '@/types/parking';
import type { RecognitionQueryState } from '@/types/recognition';
import { getDateRangeLastDays } from '@/utils/date';

const safeStorage = {
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
    } catch {}
  },
  removeItem(key: string): void {
    try {
      window.localStorage.removeItem(key);
    } catch {}
  },
};

const createParkingDefault = (): ParkingQueryState => ({
  plateNumber: '',
  timeRange: getDateRangeLastDays(7),
  statuses: [],
  parkNo: '',
  pageNo: 1,
  pageSize: 20,
  sortField: 'entryTime',
  sortOrder: 'desc',
});

const createRecognitionDefault = (): RecognitionQueryState => ({
  recognitionType: '',
  timeRange: getDateRangeLastDays(7),
  minAccuracy: 90,
  plateNumber: '',
  pageNo: 1,
  pageSize: 20,
  sortField: 'recognitionTime',
  sortOrder: 'desc',
});

const sanitizeRecordStatuses = (statuses: unknown): VehicleRecordStatus[] => {
  if (!Array.isArray(statuses)) {
    return [];
  }
  return statuses.filter((item): item is VehicleRecordStatus => item === '未出场' || item === '已出场');
};

const createDataCenterDefault = (): DataCenterQueryState => ({
  plateNumber: '',
  timeRange: getDateRangeLastDays(30),
  statuses: [],
  parkNo: '',
  pageNo: 1,
  pageSize: 20,
  sortField: 'entryTime',
  sortOrder: 'desc',
  rangePreset: 'LAST_30_DAYS',
});

export const useQueryStore = defineStore(
  'query-store',
  {
    state: () => ({
      parking: createParkingDefault(),
      recognition: createRecognitionDefault(),
      datacenter: createDataCenterDefault(),
      fieldOrder: {
        parking: ['plateNumber', 'timeRange', 'statuses', 'parkNo'],
        recognition: ['recognitionType', 'timeRange', 'minAccuracy', 'plateNumber'],
        datacenter: ['rangePreset', 'plateNumber', 'timeRange', 'statuses', 'parkNo'],
      },
    }),
    actions: {
      resetParking() {
        this.parking = createParkingDefault();
      },
      resetRecognition() {
        this.recognition = createRecognitionDefault();
      },
      resetDataCenter() {
        this.datacenter = createDataCenterDefault();
      },
      ensureDefaults() {
        if (!this.parking.timeRange?.[0] || !this.parking.timeRange?.[1]) {
          this.parking.timeRange = getDateRangeLastDays(7);
        }
        this.parking.statuses = sanitizeRecordStatuses(this.parking.statuses);
        if (!this.recognition.timeRange?.[0] || !this.recognition.timeRange?.[1]) {
          this.recognition.timeRange = getDateRangeLastDays(7);
        }
        if (!this.datacenter.timeRange?.[0] || !this.datacenter.timeRange?.[1]) {
          this.datacenter.timeRange = getDateRangeLastDays(30);
        }
        this.datacenter.statuses = sanitizeRecordStatuses(this.datacenter.statuses);
      },
    },
    persist: {
      key: 'smart-parking-query',
      storage: safeStorage,
    },
  },
);
