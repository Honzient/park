<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">系统管理 / 日志管理</h2>
        <p class="page-desc">操作日志与登录日志审计（含 IP、设备、状态）</p>
      </div>
    </div>

    <MD3Card>
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="按用户、IP、内容搜索" clearable @keyup.enter="onSearch" @clear="onSearch" />
        <el-segmented v-model="activeTab" :options="tabOptions" @change="onTabChange" />
      </div>

      <el-table
        v-if="activeTab === 'operation'"
        v-loading="loading"
        :data="operationRecords"
        table-layout="fixed"
        stripe
      >
        <el-table-column prop="operatorName" label="操作人" width="120" />
        <el-table-column prop="operationContent" label="操作内容" min-width="280" show-overflow-tooltip />
        <el-table-column prop="operationTime" label="操作时间" width="180" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="140" show-overflow-tooltip />
        <el-table-column prop="device" label="设备" min-width="180" show-overflow-tooltip />
      </el-table>

      <el-table v-else v-loading="loading" :data="loginRecords" table-layout="fixed" stripe>
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="loginTime" label="登录时间" width="180" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="140" show-overflow-tooltip />
        <el-table-column prop="device" label="设备" min-width="180" show-overflow-tooltip />
        <el-table-column prop="loginStatus" label="状态" width="120">
          <template #default="scope">
            <el-tag :type="scope.row.loginStatus === 'SUCCESS' ? 'success' : 'danger'">
              {{ loginStatusLabel(scope.row.loginStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="180" show-overflow-tooltip />
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="pageNo"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          background
          layout="total, sizes, prev, pager, next"
          :total="total"
          @size-change="loadData"
          @current-change="loadData"
        />
      </div>
    </MD3Card>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import MD3Card from '@/components/MD3Card.vue';
import { queryLoginLogs, queryOperationLogs } from '@/api/admin';
import type { LoginLog, OperationLog } from '@/types/admin';

type LogTab = 'operation' | 'login';

const activeTab = ref<LogTab>('operation');
const tabOptions = [
  { label: '操作日志', value: 'operation' },
  { label: '登录日志', value: 'login' },
];

const loading = ref(false);
const keyword = ref('');
const pageNo = ref(1);
const pageSize = ref(20);
const total = ref(0);
const operationRecords = ref<OperationLog[]>([]);
const loginRecords = ref<LoginLog[]>([]);

const loadData = async (): Promise<void> => {
  loading.value = true;
  try {
    if (activeTab.value === 'operation') {
      const page = await queryOperationLogs({
        pageNo: pageNo.value,
        pageSize: pageSize.value,
        keyword: keyword.value || undefined,
      });
      operationRecords.value = page.records;
      total.value = page.total;
      return;
    }

    const page = await queryLoginLogs({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: keyword.value || undefined,
    });
    loginRecords.value = page.records;
    total.value = page.total;
  } finally {
    loading.value = false;
  }
};

const loginStatusLabel = (status: string): string => {
  return status === 'SUCCESS' ? '成功' : '失败';
};

const onSearch = (): void => {
  pageNo.value = 1;
  void loadData();
};

const onTabChange = (): void => {
  pageNo.value = 1;
  void loadData();
};

onMounted(() => {
  void loadData();
});
</script>

<style scoped>
.toolbar {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  margin-bottom: 12px;
}

.pagination-wrap {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 768px) {
  .toolbar {
    grid-template-columns: 1fr;
  }
}
</style>

