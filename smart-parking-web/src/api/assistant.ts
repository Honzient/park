import { request } from './http';
import type { AssistantCapability, AssistantChatRequest, AssistantChatResponse } from '@/types/assistant';

const ASSISTANT_REQUEST_TIMEOUT_MS = 10 * 60 * 1000;

export const fetchAssistantCapabilities = (): Promise<AssistantCapability[]> => {
  return request<AssistantCapability[]>({
    url: '/assistant/capabilities',
    method: 'GET',
  });
};

export const sendAssistantMessage = (payload: AssistantChatRequest): Promise<AssistantChatResponse> => {
  return request<AssistantChatResponse>({
    url: '/assistant/chat',
    method: 'POST',
    timeout: ASSISTANT_REQUEST_TIMEOUT_MS,
    data: payload,
  });
};
