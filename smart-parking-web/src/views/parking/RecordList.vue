<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">智慧停车 / 车辆进出记录</h2>
        <p class="page-desc">支持自动查询、条件拖拽排序、分页与列排序</p>
      </div>
    </div>

    <MD3Card>
      <div class="query-panel query-wrap">
        <el-button class="floating-reset" circle :icon="RefreshRight" @click="resetAll" />

        <div class="query-top">
          <span class="summary-pill">{{ summary }}</span>
        </div>

        <DraggableFieldGroup v-model="fieldOrder" :fields="fieldDefs">
          <template #plateNumber>
            <el-input
              v-model="queryStore.parking.plateNumber"
              placeholder="车牌号模糊匹配，例如：粤B"
              clearable
            />
          </template>

          <template #timeRange>
            <el-date-picker
              v-model="queryStore.parking.timeRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 100%"
            />
          </template>

          <template #statuses>
            <el-select
              v-model="queryStore.parking.statuses"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="状态多选"
              style="width: 100%"
            >
              <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </template>

          <template #parkNo>
            <el-input v-model="queryStore.parking.parkNo" placeholder="车位号精确匹配，例如：A-01" clearable />
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
      title="当前结果超过 1000 条，系统已自动分页查询"
    />

    <MD3Card>
      <SkeletonTable :loading="firstLoading" :rows="8">
        <el-table v-loading="loading" :data="records" table-layout="fixed" stripe @sort-change="handleSort">
          <el-table-column prop="plateNumber" label="车牌号" min-width="130" sortable="custom" show-overflow-tooltip />
          <el-table-column prop="parkNo" label="分配车位" min-width="110" sortable="custom" show-overflow-tooltip />
          <el-table-column prop="entryTime" label="入场时间" min-width="180" sortable="custom" show-overflow-tooltip />
          <el-table-column prop="exitTime" label="出场时间" min-width="180" sortable="custom" show-overflow-tooltip>
            <template #default="scope">
              <el-button v-if="scope.row.notExited" link type="warning" @click="openDetail(scope.row.id)">
                未出场
              </el-button>
              <span v-else>{{ scope.row.exitTime }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="duration" label="停车时长" min-width="120" sortable="custom" show-overflow-tooltip />
          <el-table-column prop="fee" label="停车费用(元)" min-width="120" sortable="custom">
            <template #default="scope">
              <span class="fee-text">{{ Number(scope.row.fee).toFixed(2) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" min-width="110" sortable="custom">
            <template #default="scope">
              <el-tag :type="statusTag(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" fixed="right" width="220">
            <template #default="scope">
              <div class="row-actions">
                <el-button size="small" link type="primary" @click="openDetail(scope.row.id)">查看详情</el-button>
                <el-button size="small" link type="success" :disabled="!scope.row.notExited" @click="settleRecord(scope.row)">缴费</el-button>
                <el-button v-has-permi="'parking:query'" size="small" link type="primary" @click="openEdit(scope.row)">编辑</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrap">
          <el-pagination
            v-model:current-page="queryStore.parking.pageNo"
            v-model:page-size="queryStore.parking.pageSize"
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

    <el-drawer v-model="detailVisible" title="车辆详情" size="420px">
      <el-skeleton :loading="detailLoading" animated :rows="6">
        <template v-if="detailRecord">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="车牌号">{{ detailRecord.plateNumber }}</el-descriptions-item>
            <el-descriptions-item label="车位号">{{ detailRecord.parkNo }}</el-descriptions-item>
            <el-descriptions-item label="入场时间">{{ detailRecord.entryTime }}</el-descriptions-item>
            <el-descriptions-item label="出场时间">{{ detailRecord.exitTime || '未出场' }}</el-descriptions-item>
            <el-descriptions-item label="停车时长">{{ detailRecord.duration }}</el-descriptions-item>
            <el-descriptions-item label="停车费用">{{ Number(detailRecord.fee).toFixed(2) }} 元</el-descriptions-item>
          </el-descriptions>
        </template>
      </el-skeleton>
    </el-drawer>

    <el-dialog v-model="editVisible" title="编辑进出记录" width="520px">
      <el-form label-position="top" :model="editForm">
        <el-form-item label="车牌号" required>
          <el-input v-model="editForm.plateNumber" />
        </el-form-item>
        <el-form-item label="车位号" required>
          <el-input v-model="editForm.parkNo" />
        </el-form-item>
        <el-form-item label="入场时间" required>
          <el-date-picker
            v-model="editForm.entryTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="出场时间">
          <el-date-picker
            v-model="editForm.exitTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            clearable
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="停车费用(元)" required>
          <el-input-number v-model="editForm.fee" :precision="2" :min="0" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态" required>
          <el-select v-model="editForm.status" style="width: 100%">
            <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { RefreshRight } from '@element-plus/icons-vue';
import MD3Card from '@/components/MD3Card.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import DraggableFieldGroup from '@/components/AdvancedSearch/DraggableFieldGroup.vue';
import { fetchParkingDetail, queryParkingRecords, updateParkingRecord } from '@/api/parking';
import { useDebouncedSearch } from '@/hooks/useDebouncedSearch';
import { useQueryStore } from '@/store/query';
import { getDateRangeLastDays, isRangeReversed, withDayBoundary } from '@/utils/date';
import type { ParkingQueryPayload, ParkingRecord, ParkingRecordUpdatePayload, VehicleRecordStatus } from '@/types/parking';

const queryStore = useQueryStore();
queryStore.ensureDefaults();


const fieldDefs = [
  { key: 'plateNumber', label: '车牌号' },
  { key: 'timeRange', label: '时间范围' },
  { key: 'statuses', label: '状态' },
  { key: 'parkNo', label: '车位号' },
];

const statusOptions = [
  { label: '未出场', value: '未出场' },
  { label: '已出场', value: '已出场' },
] as const;

const statusLabelMap: Record<VehicleRecordStatus, string> = {
  未出场: '未出场',
  已出场: '已出场',
};

const statusTagMap: Record<VehicleRecordStatus, 'success' | 'warning' | 'danger' | 'info'> = {
  未出场: 'warning',
  已出场: 'success',
};

const loading = ref(false);
const firstLoading = ref(true);
const records = ref<ParkingRecord[]>([]);
const total = ref(0);

const detailVisible = ref(false);
const detailLoading = ref(false);
const detailRecord = ref<ParkingRecord | null>(null);

const editVisible = ref(false);
const editSaving = ref(false);
const editingId = ref<number | null>(null);
const editForm = reactive<ParkingRecordUpdatePayload>({
  plateNumber: '',
  parkNo: '',
  entryTime: '',
  exitTime: null,
  fee: 0,
  status: '未出场',
});

const fieldOrder = computed({
  get: () => queryStore.fieldOrder.parking,
  set: (value: string[]) => {
    queryStore.fieldOrder.parking = value;
  },
});

const rangeError = computed(() => (isRangeReversed(queryStore.parking.timeRange) ? '开始时间不能晚于结束时间' : ''));
const largeDataset = computed(() => total.value > 1000);

const summary = computed(() => {
  const parts: string[] = [];
  if (queryStore.parking.plateNumber) {
    parts.push(`车牌：${queryStore.parking.plateNumber}`);
  }
  if (queryStore.parking.parkNo) {
    parts.push(`车位：${queryStore.parking.parkNo}`);
  }
  if (queryStore.parking.statuses.length > 0) {
    parts.push(`状态：${queryStore.parking.statuses.map((item) => statusLabelMap[item]).join(' / ')}`);
  }
  parts.push(`时间：${queryStore.parking.timeRange[0]} 至 ${queryStore.parking.timeRange[1]}`);
  return parts.join(' | ');
});

const formatDateTime = (date: Date): string => {
  const pad = (value: number): string => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
};

const buildPayload = (): ParkingQueryPayload => {
  const [startTime, endTime] = withDayBoundary(queryStore.parking.timeRange);
  return {
    plateNumber: queryStore.parking.plateNumber || undefined,
    startTime,
    endTime,
    statuses: queryStore.parking.statuses.length > 0 ? queryStore.parking.statuses : undefined,
    parkNo: queryStore.parking.parkNo || undefined,
    pageNo: queryStore.parking.pageNo,
    pageSize: queryStore.parking.pageSize,
    sortField: queryStore.parking.sortField,
    sortOrder: queryStore.parking.sortOrder,
    advanced: true,
  };
};

const fetchData = async (): Promise<void> => {
  if (rangeError.value) {
    return;
  }

  loading.value = true;
  try {
    const pageData = await queryParkingRecords(buildPayload());
    records.value = pageData.records;
    total.value = pageData.total;
  } finally {
    loading.value = false;
    firstLoading.value = false;
  }
};

const debouncedSearch = useDebouncedSearch(() => {
  queryStore.parking.pageNo = 1;
  void fetchData();
}, 500);

watch(
  () => [
    queryStore.parking.plateNumber,
    queryStore.parking.parkNo,
    queryStore.parking.statuses.join(','),
    queryStore.parking.timeRange[0],
    queryStore.parking.timeRange[1],
  ],
  () => {
    debouncedSearch();
  },
);

const onPageChange = (): void => {
  void fetchData();
};

const handleSort = ({ prop, order }: { prop: string; order: 'ascending' | 'descending' | null }): void => {
  queryStore.parking.sortField = prop || 'entryTime';
  queryStore.parking.sortOrder = order === 'ascending' ? 'asc' : 'desc';
  void fetchData();
};

const statusLabel = (status: string): string => {
  return statusLabelMap[status as VehicleRecordStatus] || status;
};

const statusTag = (status: string): 'success' | 'warning' | 'danger' | 'info' => {
  return statusTagMap[status as VehicleRecordStatus] || 'info';
};

const resetAll = (): void => {
  queryStore.resetParking();
  queryStore.parking.timeRange = getDateRangeLastDays(7);
  void fetchData();
};

const settleRecord = async (record: ParkingRecord): Promise<void> => {
  if (!record.notExited) {
    return;
  }

  try {
    await ElMessageBox.confirm(
      `确认将 ${record.plateNumber} 结算出场吗？`,
      '出场结算',
      {
        confirmButtonText: '确认结算',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
  } catch {
    return;
  }

  try {
    await updateParkingRecord(record.id, {
      plateNumber: record.plateNumber.trim(),
      parkNo: record.parkNo.trim(),
      entryTime: record.entryTime,
      exitTime: formatDateTime(new Date()),
      fee: Number(record.fee || 0),
      status: statusOptions[1].value as VehicleRecordStatus,
    });
    ElMessage.success('出场结算成功');
    await fetchData();
  } catch {
    ElMessage.error('出场结算失败');
  }
};

const openDetail = async (id: number): Promise<void> => {
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    detailRecord.value = await fetchParkingDetail(id);
  } catch {
    ElMessage.error('详情加载失败');
  } finally {
    detailLoading.value = false;
  }
};

const openEdit = (record: ParkingRecord): void => {
  editingId.value = record.id;
  editForm.plateNumber = record.plateNumber;
  editForm.parkNo = record.parkNo;
  editForm.entryTime = record.entryTime;
  editForm.exitTime = record.exitTime;
  editForm.fee = Number(record.fee || 0);
  editForm.status = (record.status as VehicleRecordStatus) || '未出场';
  editVisible.value = true;
};

const saveEdit = async (): Promise<void> => {
  if (!editingId.value) {
    return;
  }
  if (!editForm.plateNumber.trim() || !editForm.parkNo.trim() || !editForm.entryTime) {
    ElMessage.warning('请完整填写必填字段');
    return;
  }

  editSaving.value = true;
  try {
    await updateParkingRecord(editingId.value, {
      plateNumber: editForm.plateNumber.trim(),
      parkNo: editForm.parkNo.trim(),
      entryTime: editForm.entryTime,
      exitTime: editForm.exitTime,
      fee: Number(editForm.fee),
      status: editForm.status,
    });
    ElMessage.success('记录已更新');
    editVisible.value = false;
    await fetchData();
  } finally {
    editSaving.value = false;
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

.alert-block {
  margin-top: 10px;
}

.fee-text {
  color: #d55656;
  font-weight: 600;
}

:deep(.el-table__row .row-actions) {
  opacity: 0;
  transition: opacity 0.2s ease;
}

:deep(.el-table__row:hover .row-actions) {
  opacity: 1;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>

