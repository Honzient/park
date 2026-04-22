<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">车辆识别 / 视频识别</h2>
        <p class="page-desc">上传视频文件或输入视频流地址，交由后端 YOLOv11 + PaddleOCR 识别</p>
      </div>
    </div>

    <div class="recognition-grid">
      <MD3Card class="upload-card">
        <div class="upload-head">
          <h3>视频文件</h3>
          <p>支持拖拽上传，上传后可预览和删除</p>
        </div>

        <el-radio-group v-model="sourceMode" class="mode-switch">
          <el-radio-button label="FILE">视频文件</el-radio-button>
          <el-radio-button label="STREAM">视频流</el-radio-button>
        </el-radio-group>

        <template v-if="sourceMode === 'FILE'">
          <el-upload
            ref="uploadRef"
            class="uploader"
            drag
            :show-file-list="false"
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-exceed="onExceed"
            accept="video/*"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽视频到这里，或 <em>点击选择文件</em></div>
            <template #tip>
              <div class="el-upload__tip">支持 MP4/MOV/WEBM，单次仅识别 1 个视频</div>
            </template>
          </el-upload>

          <div v-if="videoFile && videoPreviewUrl" class="preview-frame">
            <video :src="videoPreviewUrl" controls class="preview-video"></video>
            <div class="preview-meta">
              <div class="file-title">{{ videoFile.name }}</div>
              <div class="file-sub">{{ formatFileSize(videoFile.size) }}</div>
            </div>
            <el-button type="danger" plain @click="clearVideo">删除文件</el-button>
          </div>
        </template>

        <template v-else>
          <div class="stream-panel">
            <el-input v-model="streamUrl" placeholder="例如 rtsp://admin:password@192.168.1.88/live" clearable />
            <p class="stream-tip">支持 RTSP/HLS，后端会按帧采样识别并返回最高置信度结果</p>
          </div>
        </template>

        <div class="action-row">
          <el-button v-has-permi="'recognition:video'" class="gradient-btn" :loading="loading" @click="analyzeVideo">
            上传并识别
          </el-button>
        </div>
      </MD3Card>

      <MD3Card class="result-card">
        <h3 class="result-title">识别结果</h3>
        <el-empty v-if="!result" description="暂无识别结果" />
        <template v-else>
          <div class="plate-panel">
            <div class="plate-label">车牌号</div>
            <div class="plate-value">{{ result.plateNumber || '未识别到' }}</div>
          </div>

          <div class="confidence-panel">
            <div class="confidence-text">
              <span>置信度</span>
              <strong>{{ confidenceText }}</strong>
            </div>
            <el-progress :percentage="confidencePercent" :stroke-width="12" :show-text="false" />
          </div>

          <el-descriptions :column="1" border>
            <el-descriptions-item label="识别类型">{{ result.recognitionType === 'VIDEO' ? '视频识别' : result.recognitionType }}</el-descriptions-item>
            <el-descriptions-item label="来源">{{ result.source }}</el-descriptions-item>
          </el-descriptions>

          <el-alert
            class="result-alert"
            :type="confidencePercent < 90 ? 'warning' : 'success'"
            :title="confidencePercent < 90 ? '置信度低于 90%，建议人工复核' : '识别置信度达标'"
            :closable="false"
          />
        </template>
      </MD3Card>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus';
import { UploadFilled } from '@element-plus/icons-vue';
import MD3Card from '@/components/MD3Card.vue';
import { recognizeVideo } from '@/api/recognition';
import { useAuthStore } from '@/store/auth';
import type { MediaRecognitionResult } from '@/types/recognition';

const uploadRef = ref<UploadInstance>();
const authStore = useAuthStore();
const loading = ref(false);
const sourceMode = ref<'FILE' | 'STREAM'>('FILE');
const videoFile = ref<File | null>(null);
const videoPreviewUrl = ref('');
const streamUrl = ref('');
const result = ref<MediaRecognitionResult | null>(null);

const revokeVideoPreviewUrl = (): void => {
  if (videoPreviewUrl.value) {
    URL.revokeObjectURL(videoPreviewUrl.value);
    videoPreviewUrl.value = '';
  }
};

const onFileChange = (file: UploadFile): void => {
  const raw = file.raw;
  if (!raw) {
    return;
  }
  revokeVideoPreviewUrl();
  videoFile.value = raw;
  videoPreviewUrl.value = URL.createObjectURL(raw);
};

const onExceed = (): void => {
  ElMessage.warning('单次只能上传 1 个视频，请先删除当前文件');
};

const clearVideo = (): void => {
  videoFile.value = null;
  revokeVideoPreviewUrl();
  uploadRef.value?.clearFiles();
};

const analyzeVideo = async (): Promise<void> => {
  if (!authStore.hasPermission('recognition:video')) {
    ElMessage.error('Forbidden: no permission to use video recognition');
    return;
  }

  loading.value = true;
  try {
    if (sourceMode.value === 'FILE') {
      if (!videoFile.value) {
        ElMessage.warning('请先选择视频文件');
        return;
      }
      result.value = await recognizeVideo({ file: videoFile.value });
    } else {
      const source = streamUrl.value.trim();
      if (!source) {
        ElMessage.warning('请输入视频流地址');
        return;
      }
      result.value = await recognizeVideo({ streamUrl: source });
    }
    ElMessage.success('识别完成');
  } finally {
    loading.value = false;
  }
};

const confidencePercent = computed(() => {
  if (!result.value) {
    return 0;
  }
  const value = Number(result.value.accuracy);
  return Math.min(100, Math.max(0, Number.isFinite(value) ? Number(value.toFixed(2)) : 0));
});

const confidenceText = computed(() => `${confidencePercent.value.toFixed(2)}%`);

const formatFileSize = (size: number): string => {
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / (1024 * 1024)).toFixed(2)} MB`;
};

onBeforeUnmount(() => {
  revokeVideoPreviewUrl();
});
</script>

<style scoped>
.recognition-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(320px, 1fr);
  gap: 16px;
}

.upload-card,
.result-card {
  background: rgba(255, 255, 255, 0.74);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.upload-head h3,
.result-title {
  margin: 0;
  font-size: 18px;
}

.upload-head p {
  margin: 6px 0 14px;
  color: var(--text-sub);
  font-size: 13px;
}

.mode-switch {
  margin-bottom: 12px;
}

.uploader {
  border-radius: 14px;
  overflow: hidden;
}

.uploader :deep(.el-upload-dragger) {
  border: 1px dashed #bdd0ff;
  border-radius: 14px;
  background: #fcfdff;
}

.preview-frame {
  margin-top: 14px;
  border: 1px solid var(--border);
  border-radius: 14px;
  background: #fff;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.preview-video {
  width: 100%;
  max-height: 260px;
  border-radius: 10px;
  background: #0e1427;
}

.preview-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.file-title {
  font-weight: 600;
  word-break: break-all;
}

.file-sub {
  color: var(--text-sub);
  font-size: 12px;
}

.stream-panel {
  border: 1px solid var(--border);
  border-radius: 14px;
  background: #fff;
  padding: 12px;
}

.stream-tip {
  margin: 8px 0 0;
  color: var(--text-sub);
  font-size: 12px;
}

.action-row {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.plate-panel {
  border: 1px solid #d8e5ff;
  background: #f8fbff;
  border-radius: 14px;
  padding: 12px;
  margin-bottom: 12px;
}

.plate-label {
  font-size: 12px;
  color: var(--text-sub);
}

.plate-value {
  margin-top: 6px;
  font-size: 26px;
  line-height: 1.2;
  letter-spacing: 1px;
  font-weight: 700;
}

.confidence-panel {
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 12px;
  margin-bottom: 12px;
  background: #fff;
}

.confidence-text {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.confidence-text strong {
  font-size: 16px;
}

.result-alert {
  margin-top: 12px;
}

@media (max-width: 992px) {
  .recognition-grid {
    grid-template-columns: 1fr;
  }
}
</style>
