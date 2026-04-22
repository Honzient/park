import type { SortOrder } from './common';

export type RecognitionType = 'IMAGE' | 'VIDEO';

export interface RecognitionQueryState {
  recognitionType: '' | RecognitionType;
  timeRange: [string, string];
  minAccuracy: number;
  plateNumber: string;
  pageNo: number;
  pageSize: number;
  sortField: string;
  sortOrder: SortOrder;
}

export interface RecognitionQueryPayload {
  recognitionType?: RecognitionType;
  startTime?: string;
  endTime?: string;
  minAccuracy: number;
  plateNumber?: string;
  pageNo: number;
  pageSize: number;
  sortField: string;
  sortOrder: SortOrder;
  advanced: boolean;
}

export interface RecognitionRecord {
  id: number;
  plateNumber: string;
  recognitionTime: string;
  accuracy: number;
  recognitionType: RecognitionType;
  sourceUrl: string;
}

export interface MediaRecognitionResult {
  recognitionType: RecognitionType;
  plateNumber: string;
  accuracy: number;
  source: string;
  cameraAccessGuide: string;
}
