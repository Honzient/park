export interface AssistantCapability {
  code: string;
  name: string;
  description: string;
  readOnly: boolean;
  confirmationRequired: boolean;
}

export interface AssistantPendingAction {
  capabilityCode: string;
  summary: string;
  params: Record<string, unknown>;
}

export interface AssistantDownloadFile {
  fileName: string;
  contentType: string;
  fileBytes: string;
}

export interface AssistantChatRequest {
  message: string;
  confirm?: boolean;
  pendingAction?: Record<string, unknown> | null;
  history?: AssistantConversationMessage[];
}

export interface AssistantChatResponse {
  message: string;
  matchedCapabilityCode: string | null;
  requiresConfirmation: boolean;
  pendingAction: AssistantPendingAction | null;
  data: unknown;
  suggestions: string[];
  capabilities: AssistantCapability[];
}

export interface AssistantConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}
