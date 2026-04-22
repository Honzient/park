
<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">首页</h2>
        <p class="page-desc">实时监控车位状态、车辆进出与收入趋势</p>
      </div>
      <div class="header-actions">
        <el-radio-group v-model="range" size="default">
          <el-radio-button label="TODAY">今日</el-radio-button>
          <el-radio-button label="THIS_WEEK">本周</el-radio-button>
          <el-radio-button label="THIS_MONTH">本月</el-radio-button>
        </el-radio-group>
        <el-tag :type="wsConnected ? 'success' : 'danger'">{{ wsConnected ? 'WebSocket Connected' : 'WebSocket Reconnecting' }}</el-tag>
        <el-button class="gradient-btn" @click="refreshNow">立即刷新</el-button>
      </div>
    </div>

    <SkeletonTable :loading="firstLoading" :rows="6">
      <div class="card-grid">
        <MD3Card v-for="item in cardItems" :key="item.key" class="clickable-card" @click="onCardClick(item.key)">
          <div class="card-content">
            <p>{{ item.label }}</p>
            <h3 :class="item.valueClass">{{ item.value }}</h3>
            <small v-if="item.tip">{{ item.tip }}</small>
          </div>
        </MD3Card>
      </div>

      <div class="panel-grid">
        <MD3Card>
          <div class="panel-head">
            <h3>实时车位状态图（8x6）</h3>
            <span class="panel-sub">共 {{ realtime?.spots.length || 0 }} 个车位</span>
          </div>
          <div ref="spotChartRef" class="chart-box"></div>
        </MD3Card>

        <MD3Card>
          <div class="panel-head">
            <h3>最新进出记录</h3>
            <span class="panel-sub">每 5 秒更新</span>
          </div>
          <el-table v-loading="loading" :data="recentRecords" height="380" table-layout="fixed" stripe>
            <el-table-column prop="plateNumber" label="车牌号" min-width="108" show-overflow-tooltip />
            <el-table-column prop="entryTime" label="入场时间" min-width="140" />
            <el-table-column prop="exitTime" label="出场时间" min-width="140">
              <template #default="scope">
                <span>{{ scope.row.exitTime || '未出场' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="92" align="center">
              <template #default="scope">
                <el-tag :type="scope.row.exitTime ? 'success' : 'warning'">{{ scope.row.exitTime ? '已出场' : '在场' }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </MD3Card>
      </div>

      <MD3Card>
        <div class="panel-head">
          <h3>今日车流量与收入趋势</h3>
          <span class="panel-sub">范围：{{ rangeLabel }}</span>
        </div>
        <div ref="trendChartRef" class="chart-box trend-box"></div>
      </MD3Card>
    </SkeletonTable>
    <el-drawer v-model="spotDrawerVisible" title="车位详情" size="460px">
      <el-table :data="drawerSpots" table-layout="fixed" stripe>
        <el-table-column prop="spotNo" label="车位号" width="90" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="scope">
            <el-tag :type="spotTagType(scope.row.status)">{{ spotLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="plateNumber" label="车牌号" min-width="120" show-overflow-tooltip>
          <template #default="scope">
            <span>{{ scope.row.plateNumber || '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="entryTime" label="入场时间" min-width="140" show-overflow-tooltip>
          <template #default="scope">
            <span>{{ scope.row.entryTime || '--' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import * as echarts from 'echarts';
import MD3Card from '@/components/MD3Card.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import { fetchDashboardRealtime } from '@/api/dashboard';
import type { DashboardRealtime, DashboardSpot } from '@/types/dashboard';

type DashboardRange = 'TODAY' | 'THIS_WEEK' | 'THIS_MONTH';
type CardKey = 'total' | 'occupied' | 'free' | 'income';
type SpotFilter = 'ALL' | 'OCCUPIED' | 'FREE';

const firstLoading = ref(true);
const loading = ref(false);
const range = ref<DashboardRange>('TODAY');
const realtime = ref<DashboardRealtime | null>(null);
const wsConnected = ref(false);

const spotDrawerVisible = ref(false);
const spotFilter = ref<SpotFilter>('ALL');

const spotChartRef = ref<HTMLDivElement>();
const trendChartRef = ref<HTMLDivElement>();
let spotChart: echarts.ECharts | null = null;
let trendChart: echarts.ECharts | null = null;
let refreshTimer: number | null = null;
let reconnectTimer: number | null = null;
let dashboardSocket: WebSocket | null = null;

const rangeLabel = computed(() => {
  if (range.value === 'THIS_WEEK') {
    return '本周';
  }
  if (range.value === 'THIS_MONTH') {
    return '本月';
  }
  return '今日';
});

const cardItems = computed(() => {
  const cards = realtime.value?.cards;
  if (!cards) {
    return [] as Array<{ key: CardKey; label: string; value: string; valueClass?: string; tip?: string }>;
  }

  const trend = cards.incomeTrendPercent;
  const trendText = `${trend >= 0 ? '▲' : '▼'} ${Math.abs(trend).toFixed(2)}%`;

  return [
    {
      key: 'total' as CardKey,
      label: '车位总数',
      value: String(cards.totalSpots),
      tip: '实时统计',
    },
    {
      key: 'occupied' as CardKey,
      label: '已占车位',
      value: String(cards.occupiedSpots),
      valueClass: 'danger-text',
      tip: '点击查看详情',
    },
    {
      key: 'free' as CardKey,
      label: '空闲车位',
      value: String(cards.freeSpots),
      valueClass: 'success-text',
      tip: '点击查看详情',
    },
    {
      key: 'income' as CardKey,
      label: '今日收入',
      value: `￥${Number(cards.todayIncome).toFixed(2)}`,
      tip: `趋势 ${trendText}`,
    },
  ];
});

const recentRecords = computed(() => realtime.value?.recentRecords || []);

const drawerSpots = computed(() => {
  const spots = realtime.value?.spots || [];
  if (spotFilter.value === 'ALL') {
    return spots;
  }
  return spots.filter((item) => item.status === spotFilter.value);
});

const normalizeStatusLabel = (status: string): string => {
  const mapping: Record<string, string> = {
    FREE: '空闲',
    OCCUPIED: '占用',
    MAINTENANCE: '维修',
    RESERVED: '预约',
  };
  return mapping[status] || status;
};

const spotLabel = (status: string): string => normalizeStatusLabel(status);

const spotTagType = (status: string): 'success' | 'warning' | 'danger' | 'info' => {
  const mapping: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
    FREE: 'success',
    OCCUPIED: 'danger',
    MAINTENANCE: 'warning',
    RESERVED: 'info',
  };
  return mapping[status] || 'info';
};

const statusToValue = (status: string): number => {
  if (status === 'OCCUPIED') {
    return 1;
  }
  if (status === 'MAINTENANCE') {
    return 2;
  }
  if (status === 'RESERVED') {
    return 3;
  }
  return 0;
};

const parseSpotCoord = (spotNo: string): { x: number; y: number } | null => {
  const match = /^([A-Z])-(\d{1,2})$/.exec(spotNo);
  if (!match) {
    return null;
  }
  const rowCode = match[1]?.charCodeAt(0) || 65;
  const col = Number(match[2]) - 1;
  const row = rowCode - 65;
  if (Number.isNaN(col) || col < 0 || col > 7 || row < 0 || row > 5) {
    return null;
  }
  return { x: col, y: row };
};
const renderSpotChart = (): void => {
  if (!spotChartRef.value || !realtime.value) {
    return;
  }

  if (!spotChart) {
    spotChart = echarts.init(spotChartRef.value);
  }

  const xLabels = Array.from({ length: 8 }, (_item, index) => `${String(index + 1).padStart(2, '0')}`);
  const yLabels = ['A', 'B', 'C', 'D', 'E', 'F'];

  const chartData = realtime.value.spots
    .map((spot) => {
      const coord = parseSpotCoord(spot.spotNo);
      if (!coord) {
        return null;
      }
      return {
        value: [coord.x, coord.y, statusToValue(spot.status)] as [number, number, number],
        spot,
      };
    })
    .filter((item): item is { value: [number, number, number]; spot: DashboardSpot } => item !== null);

  spotChart.off('click');
  spotChart.on('click', (params: echarts.ECElementEvent) => {
    const data = params.data as { spot?: DashboardSpot };
    if (data?.spot) {
      spotFilter.value = data.spot.status === 'FREE' ? 'FREE' : data.spot.status === 'OCCUPIED' ? 'OCCUPIED' : 'ALL';
      spotDrawerVisible.value = true;
    }
  });

  spotChart.setOption({
    tooltip: {
      formatter: (params: { data?: { spot?: DashboardSpot } }) => {
        const spot = params.data?.spot;
        if (!spot) {
          return '无数据';
        }
        const plate = spot.plateNumber || '未占用';
        const entry = spot.entryTime || '--';
        return `${spot.spotNo}<br/>状态：${spotLabel(spot.status)}<br/>车牌：${plate}<br/>入场：${entry}`;
      },
    },
    grid: {
      left: 30,
      right: 20,
      top: 20,
      bottom: 16,
    },
    xAxis: {
      type: 'category',
      data: xLabels,
      splitArea: { show: true },
      axisTick: { show: false },
      axisLabel: { show: false },
      axisLine: { show: false },
    },
    yAxis: {
      type: 'category',
      data: yLabels,
      splitArea: { show: true },
      axisTick: { show: false },
      axisLabel: { show: false },
      axisLine: { show: false },
    },
    visualMap: {
      show: false,
      min: 0,
      max: 3,
      inRange: {
        color: ['#67C23A', '#F56C6C', '#E6A23C', '#909399'],
      },
    },
    series: [
      {
        type: 'heatmap',
        data: chartData,
        label: {
          show: true,
          color: '#fff',
          formatter: (params: { data?: { spot?: DashboardSpot } }) => params.data?.spot?.spotNo || '',
          fontSize: 11,
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowColor: 'rgba(0, 0, 0, 0.3)',
          },
        },
      },
    ],
  });
};

const renderTrendChart = (): void => {
  if (!trendChartRef.value || !realtime.value) {
    return;
  }

  if (!trendChart) {
    trendChart = echarts.init(trendChartRef.value);
  }

  trendChart.setOption({
    tooltip: {
      trigger: 'axis',
    },
    legend: {
      data: ['车流量', '收入'],
      right: 12,
      bottom: 0,
    },
    grid: {
      left: 45,
      right: 45,
      top: 40,
      bottom: 60,
    },
    xAxis: {
      type: 'category',
      data: realtime.value.trend.map((item) => item.label),
      axisTick: { show: false },
    },
    yAxis: [
      {
        type: 'value',
        name: '车流量',
      },
      {
        type: 'value',
        name: '收入(元)',
      },
    ],
    series: [
      {
        name: '车流量',
        type: 'line',
        smooth: true,
        data: realtime.value.trend.map((item) => item.traffic),
        lineStyle: { width: 3, color: '#4361ee' },
        itemStyle: { color: '#4361ee' },
        areaStyle: { color: 'rgba(67,97,238,0.16)' },
      },
      {
        name: '收入',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        data: realtime.value.trend.map((item) => Number(item.income)),
        lineStyle: { width: 3, color: '#f72585' },
        itemStyle: { color: '#f72585' },
      },
    ],
  });
};

const applyRealtime = async (nextData: DashboardRealtime, fromSocket: boolean): Promise<void> => {
  if (fromSocket && range.value !== 'TODAY' && realtime.value) {
    realtime.value = {
      ...nextData,
      trend: realtime.value.trend,
    };
  } else {
    realtime.value = nextData;
  }

  await nextTick();
  renderSpotChart();
  renderTrendChart();
};

const buildEmptyRealtime = (): DashboardRealtime => ({
  cards: {
    totalSpots: 0,
    occupiedSpots: 0,
    freeSpots: 0,
    todayIncome: 0,
    incomeTrendPercent: 0,
  },
  spots: [],
  recentRecords: [],
  trend: [],
});

const loadRealtime = async (): Promise<void> => {
  loading.value = true;
  try {
    const data = await fetchDashboardRealtime(range.value);
    await applyRealtime(data, false);
  } catch {
    // Avoid endless skeleton when initial request fails
    if (!realtime.value) {
      realtime.value = buildEmptyRealtime();
      await nextTick();
      renderSpotChart();
      renderTrendChart();
    }
  } finally {
    loading.value = false;
    firstLoading.value = false;
  }
};

const refreshNow = (): void => {
  void loadRealtime();
};

const onCardClick = (cardKey: CardKey): void => {
  if (cardKey === 'occupied') {
    spotFilter.value = 'OCCUPIED';
    spotDrawerVisible.value = true;
    return;
  }
  if (cardKey === 'free') {
    spotFilter.value = 'FREE';
    spotDrawerVisible.value = true;
  }
};

const startWebSocket = (): void => {
  if (dashboardSocket) {
    dashboardSocket.close();
  }

  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const wsUrl = `${protocol}://${window.location.host}/ws/dashboard`;
  dashboardSocket = new WebSocket(wsUrl);

  dashboardSocket.onopen = () => {
    wsConnected.value = true;
  };

  dashboardSocket.onmessage = (event) => {
    try {
      const payload = JSON.parse(event.data) as { code?: number; data?: DashboardRealtime };
      if (payload.code === 200 && payload.data) {
        void applyRealtime(payload.data, true);
      }
    } catch {
      // ignore malformed websocket payload
    }
  };

  dashboardSocket.onclose = () => {
    wsConnected.value = false;
    if (reconnectTimer) {
      window.clearTimeout(reconnectTimer);
    }
    reconnectTimer = window.setTimeout(() => {
      startWebSocket();
    }, 3000);
  };

  dashboardSocket.onerror = () => {
    wsConnected.value = false;
  };
};

const handleResize = (): void => {
  spotChart?.resize();
  trendChart?.resize();
};

watch(
  () => range.value,
  () => {
    void loadRealtime();
  },
);

onMounted(async () => {
  await loadRealtime();
  startWebSocket();
  refreshTimer = window.setInterval(() => {
    if (range.value !== 'TODAY' || !wsConnected.value) {
      void loadRealtime();
    }
  }, 5000);
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer);
  }
  if (reconnectTimer) {
    window.clearTimeout(reconnectTimer);
  }
  if (dashboardSocket) {
    dashboardSocket.close();
    dashboardSocket = null;
  }
  spotChart?.dispose();
  trendChart?.dispose();
  spotChart = null;
  trendChart = null;
  window.removeEventListener('resize', handleResize);
});
</script>
<style scoped>
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(160px, 1fr));
  gap: 12px;
}

.clickable-card {
  cursor: pointer;
}

.card-content p {
  margin: 0;
  color: var(--text-sub);
  font-size: 13px;
}

.card-content h3 {
  margin: 6px 0;
  font-size: 30px;
  line-height: 1.1;
}

.card-content small {
  color: var(--text-sub);
}

.success-text {
  color: #67c23a;
}

.danger-text {
  color: #f56c6c;
}

.panel-grid {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 12px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  gap: 8px;
}

.panel-head h3 {
  margin: 0;
  font-size: 16px;
}

.panel-sub {
  color: var(--text-sub);
  font-size: 12px;
}

.chart-box {
  height: 380px;
}

.trend-box {
  height: 320px;
}

@media (max-width: 1200px) {
  .card-grid {
    grid-template-columns: repeat(2, minmax(160px, 1fr));
  }

  .panel-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .card-grid {
    grid-template-columns: 1fr;
  }

  .chart-box,
  .trend-box {
    height: 300px;
  }
}
</style>


