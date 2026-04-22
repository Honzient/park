<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">智慧停车 / 车位分配</h2>
        <p class="page-desc">点击车位修改状态；仅“占用”状态显示车牌号，切换为其他状态会自动清空车牌</p>
      </div>
      <el-button class="gradient-btn" :loading="loading" @click="loadData">刷新车位</el-button>
    </div>

    <MD3Card>
      <div class="panel-title">
        <h3>车位地图（8 x 6）</h3>
        <div class="legend-row">
          <span class="legend free">空闲</span>
          <span class="legend occupied">占用</span>
          <span class="legend maintenance">维修</span>
          <span class="legend reserved">预约</span>
        </div>
      </div>

      <div class="spot-grid">
        <button
          v-for="spot in spotCells"
          :key="spot.spotNo"
          type="button"
          class="spot-item"
          :class="spot.status.toLowerCase()"
          @click="openEditor(spot)"
        >
          <p class="spot-no">{{ spot.spotNo }}</p>
          <small>{{ statusLabel(spot.status) }}</small>
          <small>{{ displayPlate(spot) }}</small>
          <small>{{ displayEntryTime(spot) }}</small>
        </button>
      </div>
    </MD3Card>

    <el-dialog v-model="editorVisible" width="460px" :title="`编辑车位 ${selectedSpot?.spotNo || ''}`" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="当前状态">
          <el-input :model-value="selectedSpot ? statusLabel(selectedSpot.status) : ''" readonly />
        </el-form-item>

        <el-form-item label="目标状态">
          <el-select v-model="editForm.targetStatus" style="width: 100%">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="requirePlateInput" label="车牌号（必填）">
          <el-input v-model="editForm.plateNumber" placeholder="例如：粤B12345" clearable />
        </el-form-item>

        <el-form-item v-else-if="editForm.targetStatus === 'OCCUPIED'" label="车牌号（可选）">
          <el-input v-model="editForm.plateNumber" placeholder="不填则保留当前车牌" clearable />
        </el-form-item>

        <el-alert
          v-if="requirePlateInput"
          type="info"
          :closable="false"
          title="当前为空闲/预约，改为占用时将自动记录当前系统时间为入场时间"
        />
      </el-form>

      <template #footer>
        <div class="dialog-actions">
          <el-button @click="editorVisible = false">取消</el-button>
          <el-button class="gradient-btn" :loading="submitting" @click="submitEdit">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import MD3Card from '@/components/MD3Card.vue';
import { fetchParkingSpots, updateParkingSpotStatus } from '@/api/parking';
import type { ParkingSpot, ParkingStatus } from '@/types/parking';

const loading = ref(false);
const submitting = ref(false);
const spots = ref<ParkingSpot[]>([]);

const editorVisible = ref(false);
const selectedSpot = ref<ParkingSpot | null>(null);
const editForm = reactive<{ targetStatus: ParkingStatus; plateNumber: string }>({
  targetStatus: 'FREE',
  plateNumber: '',
});

const statusOptions: Array<{ value: ParkingStatus; label: string }> = [
  { value: 'FREE', label: '空闲' },
  { value: 'OCCUPIED', label: '占用' },
  { value: 'MAINTENANCE', label: '维修' },
  { value: 'RESERVED', label: '预约' },
];

const spotCells = computed(() => {
  const map = new Map(spots.value.map((item) => [item.spotNo, item]));
  const rows = ['A', 'B', 'C', 'D', 'E', 'F'];
  const cells: ParkingSpot[] = [];

  rows.forEach((row) => {
    for (let col = 1; col <= 8; col += 1) {
      const key = `${row}-${String(col).padStart(2, '0')}`;
      const spot = map.get(key);
      if (spot) {
        cells.push(spot);
      } else {
        cells.push({
          spotNo: key,
          status: 'FREE',
          plateNumber: null,
          entryTime: null,
        });
      }
    }
  });

  return cells;
});

const statusLabel = (status: ParkingStatus): string => {
  const mapping: Record<ParkingStatus, string> = {
    FREE: '空闲',
    OCCUPIED: '占用',
    MAINTENANCE: '维修',
    RESERVED: '预约',
  };
  return mapping[status];
};

const displayPlate = (spot: ParkingSpot): string => {
  if (spot.status !== 'OCCUPIED') {
    return '--';
  }
  return spot.plateNumber || '--';
};

const displayEntryTime = (spot: ParkingSpot): string => {
  if (spot.status !== 'OCCUPIED') {
    return '--';
  }
  return spot.entryTime || '--';
};

const requirePlateInput = computed(() => {
  if (!selectedSpot.value) {
    return false;
  }
  const from = selectedSpot.value.status;
  return (from === 'FREE' || from === 'RESERVED') && editForm.targetStatus === 'OCCUPIED';
});

watch(
  () => editForm.targetStatus,
  (targetStatus) => {
    if (targetStatus !== 'OCCUPIED') {
      editForm.plateNumber = '';
    }
  },
);

const loadData = async (): Promise<void> => {
  loading.value = true;
  try {
    spots.value = await fetchParkingSpots();
  } finally {
    loading.value = false;
  }
};

const openEditor = (spot: ParkingSpot): void => {
  selectedSpot.value = spot;
  editForm.targetStatus = spot.status;
  editForm.plateNumber = spot.status === 'OCCUPIED' ? (spot.plateNumber || '') : '';
  editorVisible.value = true;
};

const submitEdit = async (): Promise<void> => {
  if (!selectedSpot.value) {
    return;
  }

  const plate = editForm.plateNumber.trim().toUpperCase();
  if (requirePlateInput.value && !plate) {
    ElMessage.warning('空闲/预约改为占用时必须填写车牌号');
    return;
  }

  submitting.value = true;
  try {
    await updateParkingSpotStatus({
      spotNo: selectedSpot.value.spotNo,
      targetStatus: editForm.targetStatus,
      plateNumber: editForm.targetStatus === 'OCCUPIED' ? plate || undefined : undefined,
    });
    ElMessage.success('车位状态已更新');
    editorVisible.value = false;
    await loadData();
  } finally {
    submitting.value = false;
  }
};

onMounted(() => {
  void loadData();
});
</script>

<style scoped>
.panel-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  gap: 10px;
  flex-wrap: wrap;
}

.panel-title h3 {
  margin: 0;
  font-size: 16px;
}

.spot-grid {
  display: grid;
  grid-template-columns: repeat(8, minmax(90px, 1fr));
  gap: 10px;
}

.spot-item {
  min-height: 106px;
  border-radius: 12px;
  border: 1px solid var(--border);
  padding: 8px;
  display: grid;
  align-content: space-between;
  gap: 4px;
  text-align: left;
  cursor: pointer;
}

.spot-item:hover {
  border-color: #9fb5f9;
  box-shadow: 0 6px 16px rgba(67, 97, 238, 0.15);
}

.spot-no {
  margin: 0;
  font-weight: 700;
}

.spot-item.free {
  background: rgba(103, 194, 58, 0.15);
}

.spot-item.occupied {
  background: rgba(245, 108, 108, 0.16);
}

.spot-item.maintenance {
  background: rgba(230, 162, 60, 0.18);
}

.spot-item.reserved {
  background: rgba(144, 147, 153, 0.18);
}

.legend-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.legend {
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 12px;
}

.legend.free {
  background: rgba(103, 194, 58, 0.2);
}

.legend.occupied {
  background: rgba(245, 108, 108, 0.2);
}

.legend.maintenance {
  background: rgba(230, 162, 60, 0.2);
}

.legend.reserved {
  background: rgba(144, 147, 153, 0.2);
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 768px) {
  .spot-grid {
    grid-template-columns: repeat(4, minmax(70px, 1fr));
  }
}
</style>
