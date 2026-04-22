<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">车辆识别 / 识别记录</h2>
        <p class="page-desc">支持自动筛选、低准确率高亮与表格导出</p>
      </div>
      <el-button v-has-permi="'recognition:query'" class="gradient-btn" @click="handleExport">导出Excel</el-button>
    </div>

    <MD3Card>
      <div class="query-panel query-wrap">
        <el-button class="floating-reset" circle :icon="RefreshRight" @click="resetAll" />

        <div class="query-top">
          <span class="summary-pill">{{ summary }}</span>
        </div>

        <DraggableFieldGroup v-model="fieldOrder" :fields="fieldDefs">
          <template #recognitionType>
            <el-radio-group v-model="queryStore.recognition.recognitionType" class="type-group">
              <el-radio-button label="">全部</el-radio-button>
              <el-radio-button label="IMAGE">图片</el-radio-button>
              <el-radio-button label="VIDEO">视频</el-radio-button>
            </el-radio-group>
          </template>

          <template #timeRange>
            <el-date-picker
              v-model="queryStore.recognition.timeRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 100%"
            />
          </template>

          <template #minAccuracy>
            <el-slider v-model="queryStore.recognition.minAccuracy" :min="60" :max="100" :step="1" show-input />
          </template>

          <template #plateNumber>
            <el-input v-model="queryStore.recognition.plateNumber" placeholder="车牌号（模糊）" clearable />
          </template>
        </DraggableFieldGroup>

        <p v-if="rangeError" class="error-text">{{ rangeError }}</p>
      </div>
    </MD3Card>

    <el-alert
      v-if="largeDataset"
      class="alert-block"
      type="info"
      :closable="false"
      title="结果集超过1000条，已自动启用分页"
    />

    <MD3Card>
      <SkeletonTable :loading="firstLoading" :rows="8">
        <el-table
          v-loading="loading"
          :data="records"
          table-layout="fixed"
          stripe
          :row-class-name="tableRowClassName"
          @sort-change="handleSort"
        >
          <el-table-column prop="plateNumber" label="车牌号" width="140" sortable="custom" show-overflow-tooltip />
          <el-table-column prop="recognitionTime" label="识别时间" width="190" sortable="custom" show-overflow-tooltip />
          <el-table-column prop="accuracy" label="准确率(%)" width="140" sortable="custom">
            <template #default="scope">
              <span>{{ Number(scope.row.accuracy).toFixed(2) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="recognitionType" label="识别类型" width="120" sortable="custom">
            <template #default="scope">
              <el-tag :type="scope.row.recognitionType === 'VIDEO' ? 'success' : 'info'">
                {{ scope.row.recognitionType === 'VIDEO' ? '视频' : '图片' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sourceUrl" label="来源" min-width="260" show-overflow-tooltip />
        </el-table>

        <div class="pagination-wrap">
          <el-pagination
            v-model:current-page="queryStore.recognition.pageNo"
            v-model:page-size="queryStore.recognition.pageSize"
            :page-sizes="[10, 20, 50, 100]"
            background
            layout="total, sizes, prev, pager, next, jumper"
            :total="total"
            @size-change="onPageChange"
            @current-change="onPageChange"
          />
        </div>
      </SkeletonTable>
    </MD3Card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { RefreshRight } from '@element-plus/icons-vue';
import MD3Card from '@/components/MD3Card.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import DraggableFieldGroup from '@/components/AdvancedSearch/DraggableFieldGroup.vue';
import { exportRecognitionExcel, queryRecognitionRecords } from '@/api/recognition';
import { useDebouncedSearch } from '@/hooks/useDebouncedSearch';
import { useAuthStore } from '@/store/auth';
import { useQueryStore } from '@/store/query';
import { downloadBlob } from '@/utils/download';
import { getDateRangeLastDays, isRangeReversed, withDayBoundary } from '@/utils/date';
import type { RecognitionQueryPayload, RecognitionRecord } from '@/types/recognition';

const authStore = useAuthStore();
const queryStore = useQueryStore();
queryStore.ensureDefaults();


const fieldDefs = [
  { key: 'recognitionType', label: '识别类型' },
  { key: 'timeRange', label: '时间范围' },
  { key: 'minAccuracy', label: '准确率阈值(%)' },
  { key: 'plateNumber', label: '车牌号' },
];

const fieldOrder = computed({
  get: () => queryStore.fieldOrder.recognition,
  set: (value: string[]) => {
    queryStore.fieldOrder.recognition = value;
  },
});

const loading = ref(false);
const firstLoading = ref(true);
const records = ref<RecognitionRecord[]>([]);
const total = ref(0);

const rangeError = computed(() => (isRangeReversed(queryStore.recognition.timeRange) ? '开始日期不能晚于结束日期' : ''));
const largeDataset = computed(() => total.value > 1000);

const summary = computed(() => {
  const parts: string[] = [];
  if (queryStore.recognition.recognitionType) {
    parts.push(`类型：${queryStore.recognition.recognitionType === 'VIDEO' ? '视频' : '图片'}`);
  }
  parts.push(`时间：${queryStore.recognition.timeRange[0]} 至 ${queryStore.recognition.timeRange[1]}`);
  parts.push(`准确率 > ${queryStore.recognition.minAccuracy}%`);
  if (queryStore.recognition.plateNumber) {
    parts.push(`车牌：${queryStore.recognition.plateNumber}`);
  }
  return parts.join(' | ');
});

const buildPayload = (): RecognitionQueryPayload => {
  const [startTime, endTime] = withDayBoundary(queryStore.recognition.timeRange);
  return {
    recognitionType: queryStore.recognition.recognitionType || undefined,
    startTime,
    endTime,
    minAccuracy: queryStore.recognition.minAccuracy,
    plateNumber: queryStore.recognition.plateNumber || undefined,
    pageNo: queryStore.recognition.pageNo,
    pageSize: queryStore.recognition.pageSize,
    sortField: queryStore.recognition.sortField,
    sortOrder: queryStore.recognition.sortOrder,
    advanced: true,
  };
};

const fetchData = async (): Promise<void> => {
  if (rangeError.value) {
    return;
  }

  loading.value = true;
  try {
    const pageData = await queryRecognitionRecords(buildPayload());
    records.value = pageData.records;
    total.value = pageData.total;
  } finally {
    loading.value = false;
    firstLoading.value = false;
  }
};

const debouncedSearch = useDebouncedSearch(() => {
  queryStore.recognition.pageNo = 1;
  void fetchData();
}, 500);

watch(
  () => [
    queryStore.recognition.recognitionType,
    queryStore.recognition.timeRange[0],
    queryStore.recognition.timeRange[1],
    queryStore.recognition.minAccuracy,
    queryStore.recognition.plateNumber,
  ],
  () => {
    debouncedSearch();
  },
);

const onPageChange = (): void => {
  void fetchData();
};

const handleSort = ({ prop, order }: { prop: string; order: 'ascending' | 'descending' | null }): void => {
  queryStore.recognition.sortField = prop || 'recognitionTime';
  queryStore.recognition.sortOrder = order === 'ascending' ? 'asc' : 'desc';
  void fetchData();
};

const resetAll = (): void => {
  queryStore.resetRecognition();
  queryStore.recognition.timeRange = getDateRangeLastDays(7);
  void fetchData();
};

const tableRowClassName = ({ row }: { row: RecognitionRecord }): string => {
  return Number(row.accuracy) < 90 ? 'low-accuracy-row' : '';
};

const handleExport = async (): Promise<void> => {
  if (!authStore.hasPermission('recognition:query')) {
    ElMessage.error('Forbidden: no permission to export recognition records');
    return;
  }
  try {
    const blob = await exportRecognitionExcel(buildPayload());
    downloadBlob(blob, `\u8bc6\u522b\u8bb0\u5f55-${Date.now()}.xlsx`, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
    ElMessage.success('导出成功');
  } catch {
    ElMessage.error('导出失败');
  }
};

onMounted(() => {
  void fetchData();
});
</script>

<style scoped>
.query-wrap {
  position: relative;
}

.query-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 12px;
  flex-wrap: wrap;
}

.type-group {
  width: 100%;
}

.alert-block {
  margin-top: 10px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

:deep(.low-accuracy-row td) {
  background: #fff2f2 !important;
  color: #c02f2f;
}
</style>


