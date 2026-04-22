<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">车辆识别 / 图片识别</h2>
        <p class="page-desc">上传图片并交由后端处理，展示识别结果与置信度</p>
      </div>
    </div>

    <div class="recognition-grid">
      <MD3Card class="upload-card">
        <div class="upload-head">
          <h3>图片文件</h3>
          <p>支持拖拽上传，上传后可预览和删除</p>
        </div>

        <el-upload
          ref="uploadRef"
          class="uploader"
          drag
          :show-file-list="false"
          :auto-upload="false"
          :limit="1"
          :on-change="onFileChange"
          :on-exceed="onExceed"
          accept="image/*"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖拽图片到这里，或 <em>点击选择文件</em></div>
          <template #tip>
            <div class="el-upload__tip">支持 JPG/PNG/WEBP，单次仅识别 1 张</div>
          </template>
        </el-upload>

        <div v-if="selectedFile && previewUrl" class="preview-frame">
          <img :src="previewUrl" alt="图片预览" class="preview-img" />
          <div class="preview-meta">
            <div class="file-title">{{ selectedFile.name }}</div>
            <div class="file-sub">{{ formatFileSize(selectedFile.size) }}</div>
          </div>
          <el-button type="danger" plain @click="clearFile">删除文件</el-button>
        </div>

        <div class="action-row">
          <el-button v-has-permi="'recognition:image'" class="gradient-btn" :loading="loading" @click="analyzeImage">
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
            <el-descriptions-item label="识别类型">{{ result.recognitionType === 'IMAGE' ? '图片识别' : result.recognitionType }}</el-descriptions-item>
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
import { recognizeImage } from '@/api/recognition';
import { useAuthStore } from '@/store/auth';
import type { MediaRecognitionResult } from '@/types/recognition';

const uploadRef = ref<UploadInstance>();
const authStore = useAuthStore();
const loading = ref(false);
const selectedFile = ref<File | null>(null);
const previewUrl = ref('');
const result = ref<MediaRecognitionResult | null>(null);

const onFileChange = (file: UploadFile): void => {
  const raw = file.raw;
  if (!raw) {
    return;
  }
  revokePreviewUrl();
  selectedFile.value = raw;
  previewUrl.value = URL.createObjectURL(raw);
};

const onExceed = (): void => {
  ElMessage.warning('单次只能上传 1 张图片，请先删除当前文件');
};

const revokePreviewUrl = (): void => {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value);
    previewUrl.value = '';
  }
};

const clearFile = (): void => {
  selectedFile.value = null;
  revokePreviewUrl();
  uploadRef.value?.clearFiles();
};

const analyzeImage = async (): Promise<void> => {
  if (!authStore.hasPermission('recognition:image')) {
    ElMessage.error('Forbidden: no permission to use image recognition');
    return;
  }
  if (!selectedFile.value) {
    ElMessage.warning('请先选择图片');
    return;
  }

  loading.value = true;
  try {
    result.value = await recognizeImage(selectedFile.value);
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
  revokePreviewUrl();
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

.action-row {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.preview-img {
  width: 100%;
  max-height: 280px;
  object-fit: contain;
  border-radius: 10px;
  background: #f8fafc;
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


