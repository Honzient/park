<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">数据中心 / 统计大屏</h2>
        <p class="page-desc">默认近30天，支持筛选、图表联动与数据导出</p>
      </div>
      <div class="export-actions">
        <el-button v-has-permi="'datacenter:export:excel'" class="gradient-btn" @click="onExportExcel">导出表格</el-button>
        <el-button v-has-permi="'datacenter:export:pdf'" @click="onExportPdf">导出文档</el-button>
      </div>
    </div>

    <MD3Card>
      <div class="query-panel query-wrap">
        <el-button class="floating-reset" circle :icon="RefreshRight" @click="resetAll" />

        <div class="query-top">
          <span class="summary-pill">{{ filterSummary }}</span>
        </div>

        <DraggableFieldGroup v-model="fieldOrder" :fields="fieldDefs">
          <template #rangePreset>
            <el-select v-model="queryStore.datacenter.rangePreset" style="width: 100%">
              <el-option label="近30天（默认）" value="LAST_30_DAYS" />
              <el-option label="今日" value="TODAY" />
              <el-option label="本周" value="THIS_WEEK" />
              <el-option label="本月" value="THIS_MONTH" />
              <el-option label="自定义" value="CUSTOM" />
            </el-select>
          </template>

          <template #plateNumber>
            <el-input v-model="queryStore.datacenter.plateNumber" placeholder="车牌号（模糊）" clearable />
          </template>

          <template #timeRange>
            <el-date-picker
              v-model="queryStore.datacenter.timeRange"
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
              v-model="queryStore.datacenter.statuses"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="状态"
              style="width: 100%"
            >
              <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </template>

          <template #parkNo>
            <el-input v-model="queryStore.datacenter.parkNo" placeholder="车位号" clearable />
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
      title="当前结果超过1000条，列表按分页展示，图表与导出按完整筛选结果统计"
    />

    <SkeletonTable :loading="firstLoading" :rows="8">
      <div class="summary-grid">
        <MD3Card v-for="card in summaryCards" :key="card.label">
          <div class="summary-card">
            <span class="summary-card__label">{{ card.label }}</span>
            <strong class="summary-card__value">{{ card.value }}</strong>
          </div>
        </MD3Card>
      </div>

      <div class="chart-grid">
        <MD3Card>
          <div class="panel-head">
            <h3>车辆进出时间轴</h3>
            <span class="panel-sub">按完整筛选结果统计</span>
          </div>
          <div ref="timelineRef" class="chart-box"></div>
        </MD3Card>

        <MD3Card>
          <div class="panel-head">
            <h3>省份统计</h3>
            <span class="panel-sub">记录总数 {{ overviewSummary.recordCount }}</span>
          </div>
          <div ref="provinceRef" class="chart-box"></div>
        </MD3Card>
      </div>

      <MD3Card>
        <div class="panel-head">
          <h3>停车记录</h3>
        </div>

        <el-table v-loading="loading" :data="records" table-layout="fixed" stripe>
          <el-table-column prop="plateNumber" label="车牌号" min-width="120" show-overflow-tooltip />
          <el-table-column prop="parkNo" label="车位号" min-width="100" show-overflow-tooltip />
          <el-table-column prop="entryTime" label="入场时间" min-width="180" show-overflow-tooltip />
          <el-table-column prop="exitTime" label="出场时间" min-width="180" show-overflow-tooltip />
          <el-table-column prop="duration" label="停车时长" min-width="120" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" min-width="110">
            <template #default="scope">
              <el-tag :type="scope.row.notExited ? 'warning' : 'success'" effect="light">
                {{ scope.row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="fee" label="费用（元）" min-width="120">
            <template #default="scope">
              <span class="fee-text">{{ Number(scope.row.fee).toFixed(2) }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrap">
          <el-pagination
            v-model:current-page="queryStore.datacenter.pageNo"
            v-model:page-size="queryStore.datacenter.pageSize"
            :page-sizes="[10, 20, 50, 100]"
            background
            layout="total, sizes, prev, pager, next, jumper"
            :total="total"
            @size-change="onPageChange"
            @current-change="onPageChange"
          />
        </div>
      </MD3Card>
    </SkeletonTable>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { RefreshRight } from '@element-plus/icons-vue';
import * as echarts from 'echarts';
import MD3Card from '@/components/MD3Card.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import DraggableFieldGroup from '@/components/AdvancedSearch/DraggableFieldGroup.vue';
import { exportDataCenterExcel, exportDataCenterPdf, fetchDataCenterOverview, queryDataCenterRecords } from '@/api/datacenter';
import { useDebouncedSearch } from '@/hooks/useDebouncedSearch';
import { useAuthStore } from '@/store/auth';
import { useQueryStore } from '@/store/query';
import { downloadBlob } from '@/utils/download';
import {
  getDateRangeLastDays,
  getMonthRange,
  getTodayRange,
  getWeekRange,
  isRangeReversed,
  withDayBoundary,
} from '@/utils/date';
import type { DataCenterOverview, DataCenterQueryPayload, DataCenterSummary, ParkingRecord, VehicleRecordStatus } from '@/types/parking';

const authStore = useAuthStore();
const queryStore = useQueryStore();
queryStore.ensureDefaults();

const loading = ref(false);
const firstLoading = ref(true);
const total = ref(0);
const records = ref<ParkingRecord[]>([]);
const overview = ref<DataCenterOverview | null>(null);

const timelineRef = ref<HTMLDivElement>();
const provinceRef = ref<HTMLDivElement>();
let timelineChart: echarts.ECharts | null = null;
let provinceChart: echarts.ECharts | null = null;

const statusOptions = [
  { label: '未出场', value: '未出场' },
  { label: '已出场', value: '已出场' },
] as const;

const fieldDefs = [
  { key: 'rangePreset', label: '时间预设' },
  { key: 'plateNumber', label: '车牌号' },
  { key: 'timeRange', label: '时间范围' },
  { key: 'statuses', label: '状态' },
  { key: 'parkNo', label: '车位号' },
];

const fieldOrder = computed({
  get: () => queryStore.fieldOrder.datacenter,
  set: (value: string[]) => {
    queryStore.fieldOrder.datacenter = value;
  },
});

const largeDataset = computed(() => total.value > 1000);

const provinceTop5 = computed(() => {
  const items = overview.value?.provinceTop5 ?? [];
  return items.map((item) => ({
    name: item.name || '\u672a\u77e5',
    value: Number(item.value ?? 0),
  }));
});

const emptyOverviewSummary: DataCenterSummary = {
  recordCount: 0,
  activeRecordCount: 0,
  exitedRecordCount: 0,
  entryEventCount: 0,
  exitEventCount: 0,
  totalFee: 0,
  averageDurationMinutes: 0,
};

const overviewSummary = computed(() => overview.value?.summary ?? emptyOverviewSummary);

const rangeLabelMap: Record<string, string> = {
  LAST_30_DAYS: '近30天',
  TODAY: '今日',
  THIS_WEEK: '本周',
  THIS_MONTH: '本月',
  CUSTOM: '自定义',
};

const statusLabelMap: Record<VehicleRecordStatus, string> = {
  未出场: '未出场',
  已出场: '已出场',
};

const filterSummary = computed(() => {
  const parts: string[] = [];
  parts.push(`预设：${rangeLabelMap[queryStore.datacenter.rangePreset] || queryStore.datacenter.rangePreset}`);
  parts.push(`时间：${queryStore.datacenter.timeRange[0]} 至 ${queryStore.datacenter.timeRange[1]}`);
  if (queryStore.datacenter.plateNumber) {
    parts.push(`车牌：${queryStore.datacenter.plateNumber}`);
  }
  if (queryStore.datacenter.parkNo) {
    parts.push(`车位：${queryStore.datacenter.parkNo}`);
  }
  if (queryStore.datacenter.statuses.length > 0) {
    parts.push(`状态：${queryStore.datacenter.statuses.map((item) => statusLabelMap[item]).join(' / ')}`);
  }
  return parts.join(' | ');
});

const formatCurrency = (value: number): string => `¥${Number(value || 0).toFixed(2)}`;

const formatMinutes = (value: number): string => {
  const totalMinutes = Math.max(Number(value || 0), 0);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) {
    return `${minutes} 分钟`;
  }
  if (minutes === 0) {
    return `${hours} 小时`;
  }
  return `${hours} 小时 ${minutes} 分钟`;
};

const summaryCards = computed(() => [
  { label: '记录总数', value: String(overviewSummary.value.recordCount) },
  { label: '未出场记录', value: String(overviewSummary.value.activeRecordCount) },
  { label: '已出场记录', value: String(overviewSummary.value.exitedRecordCount) },
  { label: '区间入场车辆', value: String(overviewSummary.value.entryEventCount) },
  { label: '区间出场车辆', value: String(overviewSummary.value.exitEventCount) },
  { label: '累计费用', value: formatCurrency(overviewSummary.value.totalFee) },
  { label: '平均停车时长', value: formatMinutes(overviewSummary.value.averageDurationMinutes) },
]);

const rangeError = computed(() => {
  if (queryStore.datacenter.rangePreset !== 'CUSTOM') {
    return '';
  }
  return isRangeReversed(queryStore.datacenter.timeRange) ? '开始日期不能晚于结束日期' : '';
});

const applyPresetRange = (): void => {
  switch (queryStore.datacenter.rangePreset) {
    case 'TODAY':
      queryStore.datacenter.timeRange = getTodayRange();
      break;
    case 'THIS_WEEK':
      queryStore.datacenter.timeRange = getWeekRange();
      break;
    case 'THIS_MONTH':
      queryStore.datacenter.timeRange = getMonthRange();
      break;
    case 'LAST_30_DAYS':
      queryStore.datacenter.timeRange = getDateRangeLastDays(30);
      break;
    case 'CUSTOM':
      break;
    default:
      break;
  }
};

const buildPayload = (): DataCenterQueryPayload => {
  const [startTime, endTime] = withDayBoundary(queryStore.datacenter.timeRange);
  return {
    rangePreset: queryStore.datacenter.rangePreset,
    plateNumber: queryStore.datacenter.plateNumber || undefined,
    startTime,
    endTime,
    statuses: queryStore.datacenter.statuses.length ? queryStore.datacenter.statuses : undefined,
    parkNo: queryStore.datacenter.parkNo || undefined,
    pageNo: queryStore.datacenter.pageNo,
    pageSize: queryStore.datacenter.pageSize,
    sortField: queryStore.datacenter.sortField,
    sortOrder: queryStore.datacenter.sortOrder,
    advanced: authStore.hasPermission('query:advanced'),
  };
};

const renderTimeline = (): void => {
  if (!timelineRef.value || !overview.value) {
    return;
  }

  if (!timelineChart) {
    timelineChart = echarts.init(timelineRef.value);
  }

  const timelineData = overview.value.timeline || [];
  timelineChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: {
      top: 0,
      right: 0,
      data: ['入场车辆', '出场车辆'],
    },
    grid: { left: 40, right: 20, top: 50, bottom: 60 },
    xAxis: {
      type: 'category',
      data: timelineData.map((item) => item.label),
      boundaryGap: false,
      axisLabel: { rotate: timelineData.length > 7 ? 30 : 0 },
    },
    yAxis: {
      type: 'value',
      name: '车辆数',
      minInterval: 1,
    },
    series: [
      {
        name: '入场车辆',
        type: 'line',
        smooth: true,
        data: timelineData.map((item) => Number(item.entryCount)),
        lineStyle: { color: '#2a9d8f', width: 3 },
        itemStyle: { color: '#2a9d8f' },
      },
      {
        name: '出场车辆',
        type: 'line',
        smooth: true,
        data: timelineData.map((item) => Number(item.exitCount)),
        lineStyle: { color: '#e76f51', width: 3 },
        itemStyle: { color: '#e76f51' },
      },
    ],
  }, true);
};

const renderProvince = (): void => {
  if (!provinceRef.value) {
    return;
  }

  if (!provinceChart) {
    provinceChart = echarts.init(provinceRef.value);
  }

  provinceChart.setOption({
    title: provinceTop5.value.length
      ? undefined
      : {
          text: '暂无数据',
          left: 'center',
          top: 'middle',
          textStyle: {
            color: '#7a838f',
            fontSize: 14,
            fontWeight: 'normal',
          },
        },
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['45%', '70%'],
        avoidLabelOverlap: false,
        label: { show: provinceTop5.value.length > 0, formatter: '{b}: {d}%' },
        data: provinceTop5.value,
      },
    ],
  }, true);
};

const fetchData = async (): Promise<void> => {
  if (rangeError.value) {
    return;
  }

  loading.value = true;
  try {
    const payload = buildPayload();
    const [page, screenData] = await Promise.all([queryDataCenterRecords(payload), fetchDataCenterOverview(payload)]);

    records.value = page.records;
    total.value = page.total;
    overview.value = screenData;

    await nextTick();
    renderTimeline();
    renderProvince();
  } finally {
    loading.value = false;
    firstLoading.value = false;
  }
};

const debouncedFetch = useDebouncedSearch(() => {
  queryStore.datacenter.pageNo = 1;
  void fetchData();
}, 500);

watch(
  () => queryStore.datacenter.rangePreset,
  () => {
    applyPresetRange();
    queryStore.datacenter.pageNo = 1;
    void fetchData();
  },
);

watch(
  () => [
    queryStore.datacenter.plateNumber,
    queryStore.datacenter.parkNo,
    queryStore.datacenter.timeRange[0],
    queryStore.datacenter.timeRange[1],
    queryStore.datacenter.statuses.join(','),
  ],
  () => {
    debouncedFetch();
  },
);

const onPageChange = (): void => {
  void fetchData();
};

const resetAll = (): void => {
  queryStore.resetDataCenter();
  queryStore.datacenter.timeRange = getDateRangeLastDays(30);
  void fetchData();
};

const onExportExcel = async (): Promise<void> => {
  if (!authStore.hasPermission('datacenter:export:excel')) {
    ElMessage.error('当前账号没有导出数据中心表格的权限');
    return;
  }
  const blob = await exportDataCenterExcel(buildPayload());
  downloadBlob(blob, `\u6570\u636e\u4e2d\u5fc3\u505c\u8f66\u8bb0\u5f55-${Date.now()}.xlsx`, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
  ElMessage.success('表格导出成功');
};

const onExportPdf = async (): Promise<void> => {
  if (!authStore.hasPermission('datacenter:export:pdf')) {
    ElMessage.error('当前账号没有导出数据中心文档的权限');
    return;
  }
  const blob = await exportDataCenterPdf(buildPayload());
  downloadBlob(blob, `\u6570\u636e\u4e2d\u5fc3\u505c\u8f66\u8bb0\u5f55-${Date.now()}.pdf`, 'application/pdf');
  ElMessage.success('文档导出成功');
};

const handleResize = (): void => {
  timelineChart?.resize();
  provinceChart?.resize();
};

onMounted(() => {
  applyPresetRange();
  void fetchData();
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  timelineChart?.dispose();
  provinceChart?.dispose();
  timelineChart = null;
  provinceChart = null;
  window.removeEventListener('resize', handleResize);
});
</script>

<style scoped>
.export-actions {
  display: flex;
  gap: 8px;
}

.query-wrap {
  position: relative;
}

.query-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.alert-block {
  margin-top: 10px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.summary-card {
  display: flex;
  min-height: 76px;
  flex-direction: column;
  justify-content: space-between;
  gap: 10px;
}

.summary-card__label {
  color: var(--text-sub);
  font-size: 13px;
}

.summary-card__value {
  color: #163047;
  font-size: 24px;
  line-height: 1.1;
}

.chart-grid {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 12px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.panel-head h3 {
  margin: 0;
}

.panel-sub {
  color: var(--text-sub);
  font-size: 12px;
}

.chart-box {
  height: 320px;
}

.fee-text {
  color: #d55656;
  font-weight: 600;
}

.pagination-wrap {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 992px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>




