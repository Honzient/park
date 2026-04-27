<template>
  <section class="page-shell assistant-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">智能助手</h2>
        <p class="page-desc">用自然语言查询当前账号有权限访问的系统能力，写操作会先确认后执行。</p>
      </div>
      <el-button class="gradient-btn" @click="resetConversation">清空对话</el-button>
    </div>

    <MD3Card class="conversation-card">
      <div class="conversation-list">
        <div
          v-for="message in messages"
          :key="message.id"
          :class="['message-row', message.role === 'user' ? 'message-user' : 'message-assistant']"
        >
          <div class="message-bubble">
            <div class="message-role">{{ message.role === 'user' ? '你' : '助手' }}</div>
            <p class="message-text">{{ message.text }}</p>

            <div v-if="message.role === 'assistant' && message.payload" class="message-payload">
              <div v-if="message.payload.suggestions.length > 0" class="suggestion-wrap">
                <el-button
                  v-for="item in message.payload.suggestions"
                  :key="item"
                  size="small"
                  plain
                  @click="applySuggestion(item)"
                >
                  {{ item }}
                </el-button>
              </div>

              <div v-if="message.payload.pendingAction" class="pending-card">
                <div class="pending-title">{{ message.payload.pendingAction.summary }}</div>
                <div class="pending-actions">
                  <el-button type="primary" :loading="confirming" @click="confirmPending(message.payload.pendingAction)">
                    确认执行
                  </el-button>
                </div>
              </div>

              <div v-if="message.payload.data !== null && message.payload.data !== undefined" class="result-card">
                <template v-if="isPageResult(message.payload.data)">
                  <div class="result-meta">共 {{ message.payload.data.total }} 条，当前展示 {{ message.payload.data.records.length }} 条</div>
                  <el-table :data="message.payload.data.records" table-layout="fixed" stripe>
                    <el-table-column
                      v-for="column in tableColumns(message.payload.data.records)"
                      :key="column"
                      :prop="column"
                      :label="columnLabel(column)"
                      min-width="140"
                      show-overflow-tooltip
                    />
                  </el-table>
                </template>

                <template v-else-if="isObjectArray(message.payload.data)">
                  <el-table :data="message.payload.data" table-layout="fixed" stripe>
                    <el-table-column
                      v-for="column in tableColumns(message.payload.data)"
                      :key="column"
                      :prop="column"
                      :label="columnLabel(column)"
                      min-width="140"
                      show-overflow-tooltip
                    />
                  </el-table>
                </template>

                <template v-else-if="isAssistantDownloadFile(message.payload.data)">
                  <div class="result-meta">文件已生成，可直接下载。</div>
                  <div class="download-card">
                    <div class="download-name">{{ message.payload.data.fileName }}</div>
                    <el-button type="primary" plain @click="downloadAssistantFile(message.payload.data)">
                      下载文件
                    </el-button>
                  </div>
                </template>

                <template v-else-if="isPlainObject(message.payload.data)">
                  <el-descriptions :column="1" border>
                    <el-descriptions-item
                      v-for="entry in objectEntries(message.payload.data)"
                      :key="entry.key"
                      :label="columnLabel(entry.key)"
                    >
                      {{ entry.value }}
                    </el-descriptions-item>
                  </el-descriptions>
                </template>

                <pre v-else class="raw-block">{{ stringifyData(message.payload.data) }}</pre>
              </div>
            </div>
          </div>
        </div>
      </div>
    </MD3Card>

    <MD3Card class="composer-card">
      <div class="quick-actions">
        <el-button v-for="item in quickSuggestions" :key="item" size="small" plain @click="applySuggestion(item)">
          {{ item }}
        </el-button>
      </div>
      <el-input
        v-model="inputValue"
        :rows="4"
        type="textarea"
        resize="none"
        placeholder="例如：最近十天京A的车辆进出记录；导出本月数据中心记录PDF；禁用用户张三；把我的密码从OldPass1改成NewPass2"
        @keydown.ctrl.enter.prevent="submitMessage"
      />
      <div class="composer-actions">
        <span>支持 Ctrl + Enter 发送</span>
        <el-button type="primary" :loading="sending" @click="submitMessage">发送</el-button>
      </div>
    </MD3Card>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import MD3Card from '@/components/MD3Card.vue';
import { sendAssistantMessage } from '@/api/assistant';
import { downloadBlob } from '@/utils/download';
import type {
  AssistantChatResponse,
  AssistantConversationMessage,
  AssistantDownloadFile,
  AssistantPendingAction,
} from '@/types/assistant';

type MessageItem = {
  id: number;
  role: 'user' | 'assistant';
  text: string;
  payload?: AssistantChatResponse;
};

type PageResultLike = {
  records: Array<Record<string, unknown>>;
  total: number;
};

const messages = ref<MessageItem[]>([
  {
    id: 1,
    role: 'assistant',
    text: '我会按当前账号权限范围处理请求。你可以直接问“查最近10天京A的进出记录”或“导出本月数据中心记录Excel”。',
  },
]);
const inputValue = ref('');
const sending = ref(false);
const confirming = ref(false);
let messageId = 2;

const quickSuggestions = computed(() => [
  '最近十天京A的车辆进出记录',
  '查看停车记录12详情',
  '导出最近7天的视频识别记录Excel',
  '导出本月数据中心记录PDF',
  '把停车记录12的费用改成35',
  '禁用用户张三',
  '把管理员角色权限改成用户管理、角色管理',
  '把我的密码从OldPass1改成NewPass2',
]);

const appendMessage = (role: MessageItem['role'], text: string, payload?: AssistantChatResponse): void => {
  messages.value.push({
    id: messageId++,
    role,
    text,
    payload,
  });
};

const buildHistory = (): AssistantConversationMessage[] => {
  return messages.value
    .slice(-8)
    .map((item) => ({
      role: item.role,
      content: item.text,
    }));
};

const submitMessage = async (): Promise<void> => {
  const message = inputValue.value.trim();
  if (!message) {
    return;
  }

  const history = buildHistory();
  appendMessage('user', message);
  inputValue.value = '';
  sending.value = true;
  try {
    const response = await sendAssistantMessage({
      message,
      history,
    });
    maybeDownloadAssistantFile(response);
    appendMessage('assistant', response.message, response);
  } catch {
    ElMessage.error('助手请求失败');
  } finally {
    sending.value = false;
  }
};

const confirmPending = async (pendingAction: AssistantPendingAction): Promise<void> => {
  confirming.value = true;
  try {
    const response = await sendAssistantMessage({
      message: 'confirm',
      confirm: true,
      pendingAction: {
        capabilityCode: pendingAction.capabilityCode,
        params: pendingAction.params,
      },
    });
    maybeDownloadAssistantFile(response);
    appendMessage('assistant', response.message, response);
  } catch {
    ElMessage.error('确认执行失败');
  } finally {
    confirming.value = false;
  }
};

const applySuggestion = (text: string): void => {
  inputValue.value = text;
};

const resetConversation = (): void => {
  messages.value = [
    {
      id: 1,
      role: 'assistant',
      text: '对话已清空。你可以继续通过自然语言调用当前账号有权限的系统能力。',
    },
  ];
  inputValue.value = '';
  messageId = 2;
};

const isPageResult = (value: unknown): value is PageResultLike => {
  if (!isPlainObject(value)) {
    return false;
  }
  return Array.isArray(value.records) && typeof value.total === 'number';
};

const isObjectArray = (value: unknown): value is Array<Record<string, unknown>> => {
  return Array.isArray(value) && value.length > 0 && value.every((item) => isPlainObject(item));
};

const isAssistantDownloadFile = (value: unknown): value is AssistantDownloadFile => {
  if (!isPlainObject(value)) {
    return false;
  }
  return typeof value.fileName === 'string'
    && typeof value.contentType === 'string'
    && typeof value.fileBytes === 'string';
};

const isPlainObject = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
};

const tableColumns = (rows: Array<Record<string, unknown>>): string[] => {
  const first = rows[0];
  return first ? Object.keys(first) : [];
};

const objectEntries = (value: Record<string, unknown>): Array<{ key: string; value: string }> => {
  return Object.entries(value).map(([key, rawValue]) => ({
    key,
    value: stringifyData(rawValue),
  }));
};

const stringifyData = (value: unknown): string => {
  if (value === null || value === undefined) {
    return '--';
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
};

const base64ToArrayBuffer = (base64: string): ArrayBuffer => {
  const binary = window.atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
};

const downloadAssistantFile = (file: AssistantDownloadFile): void => {
  downloadBlob(base64ToArrayBuffer(file.fileBytes), file.fileName, file.contentType);
};

const maybeDownloadAssistantFile = (response: AssistantChatResponse): void => {
  if (!isAssistantDownloadFile(response.data)) {
    return;
  }
  downloadAssistantFile(response.data);
};

const columnLabel = (key: string): string => {
  const mapping: Record<string, string> = {
    id: 'ID',
    plateNumber: '车牌号',
    parkNo: '车位号',
    entryTime: '入场时间',
    exitTime: '出场时间',
    duration: '停车时长',
    fee: '费用',
    status: '状态',
    spotNo: '车位号',
    recognitionTime: '识别时间',
    accuracy: '识别置信度',
    recognitionType: '识别类型',
    sourceUrl: '来源',
    roleCode: '角色编码',
    roleName: '角色名称',
    username: '用户名',
    realName: '姓名',
    phone: '手机号',
    loginTime: '登录时间',
    loginStatus: '登录状态',
    operatorName: '操作人',
    operationContent: '操作内容',
    operationTime: '操作时间',
    permissions: '权限',
    message: '消息',
    device: '设备',
    ip: 'IP',
    currentSpotNo: '当前车位',
    recordId: '记录ID',
    lastLoginTime: '最后登录时间',
  };
  return mapping[key] || key;
};
</script>

<style scoped>
.assistant-page {
  gap: 16px;
}

.conversation-card,
.composer-card {
  overflow: hidden;
}

.conversation-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 420px;
}

.message-row {
  display: flex;
}

.message-user {
  justify-content: flex-end;
}

.message-assistant {
  justify-content: flex-start;
}

.message-bubble {
  width: min(100%, 1000px);
  padding: 14px;
  border-radius: 16px;
  border: 1px solid var(--border);
  background: #fbfcfe;
}

.message-user .message-bubble {
  background: rgba(67, 97, 238, 0.08);
}

.message-role {
  margin-bottom: 6px;
  color: var(--text-sub);
  font-size: 12px;
}

.message-text {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.7;
}

.message-payload {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.suggestion-wrap,
.quick-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.pending-card,
.result-card {
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.8);
}

.pending-title {
  font-weight: 600;
}

.pending-actions {
  margin-top: 10px;
}

.result-meta {
  margin-bottom: 10px;
  color: var(--text-sub);
  font-size: 12px;
}

.raw-block {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
}

.download-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.download-name {
  word-break: break-word;
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 12px;
  color: var(--text-sub);
  font-size: 12px;
}
</style>
