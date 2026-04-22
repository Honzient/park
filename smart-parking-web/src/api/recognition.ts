import { request } from './http';
import type { PageResult } from '@/types/common';
import type {
  MediaRecognitionResult,
  RecognitionQueryPayload,
  RecognitionRecord,
} from '@/types/recognition';

const RECOGNITION_REQUEST_TIMEOUT_MS = 240000;

export const queryRecognitionRecords = (payload: RecognitionQueryPayload): Promise<PageResult<RecognitionRecord>> => {
  return request<PageResult<RecognitionRecord>>({
    url: '/recognition/records/query',
    method: 'POST',
    data: payload,
  });
};

export const exportRecognitionExcel = (payload: RecognitionQueryPayload): Promise<Blob> => {
  return request<Blob>({
    url: '/recognition/records/export/excel',
    method: 'POST',
    data: payload,
    responseType: 'blob',
  });
};

export const recognizeImage = (file: File): Promise<MediaRecognitionResult> => {
  const formData = new FormData();
  formData.append('file', file);
  return request<MediaRecognitionResult>({
    url: '/recognition/image/analyze',
    method: 'POST',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: RECOGNITION_REQUEST_TIMEOUT_MS,
  });
};

export const recognizeVideo = (params: { file?: File; streamUrl?: string }): Promise<MediaRecognitionResult> => {
  const formData = new FormData();
  if (params.file) {
    formData.append('file', params.file);
  }
  if (params.streamUrl) {
    formData.append('streamUrl', params.streamUrl);
  }

  return request<MediaRecognitionResult>({
    url: '/recognition/video/analyze',
    method: 'POST',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: RECOGNITION_REQUEST_TIMEOUT_MS,
  });
};

export const fetchVideoAccessGuide = (): Promise<string> => {
  return request<string>({
    url: '/recognition/video/access-guide',
    method: 'GET',
  });
};
