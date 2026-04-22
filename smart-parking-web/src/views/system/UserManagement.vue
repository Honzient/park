<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">系统管理 / 用户管理</h2>
        <p class="page-desc">用户、角色、状态与最后登录时间管理</p>
      </div>
    </div>

    <MD3Card>
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="按用户名或姓名搜索" clearable @keyup.enter="onSearch" @clear="onSearch" />
        <el-select v-model="selectedRole" placeholder="选择角色" style="width: 180px">
          <el-option v-for="role in roles" :key="role.roleCode" :label="roleName(role.roleCode)" :value="role.roleCode" />
        </el-select>
        <el-button
          v-has-permi="'admin:user:assign-role'"
          class="gradient-btn"
          :disabled="!selectedIds.length || !selectedRole"
          @click="assignRole"
        >
          批量分配角色
        </el-button>
      </div>

      <el-table v-loading="loading" :data="records" table-layout="fixed" stripe @selection-change="onSelectionChange">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="username" label="用户名" min-width="120" show-overflow-tooltip />
        <el-table-column prop="realName" label="姓名" min-width="120" show-overflow-tooltip>
          <template #default="scope">
            {{ displayName(scope.row.realName, scope.row.username) }}
          </template>
        </el-table-column>
        <el-table-column prop="roleCode" label="角色" min-width="120">
          <template #default="scope">
            <el-tag :type="scope.row.roleCode === 'ADMIN' ? 'danger' : 'success'">{{ roleName(scope.row.roleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" min-width="100">
          <template #default="scope">
            <el-tag :type="scope.row.status === 'ENABLED' ? 'success' : 'info'">{{ scope.row.status === 'ENABLED' ? '启用' : '禁用' }}</el-tag>
          </template>
                </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="scope">
            <div class="row-actions">
              <el-button v-has-permi="'admin:user:assign-role'" link type="primary" @click="openEdit(scope.row)">编辑</el-button>
              <el-button
                v-has-permi="'admin:user:assign-role'"
                link
                :type="scope.row.status === 'ENABLED' ? 'warning' : 'success'"
                @click="toggleStatus(scope.row)"
              >
                {{ scope.row.status === 'ENABLED' ? '禁用' : '启用' }}
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最后登录时间" min-width="180" show-overflow-tooltip />
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
    <el-dialog v-model="editVisible" title="编辑用户" width="520px">
      <el-form label-position="top" :model="editForm">
        <el-form-item label="用户名" required>
          <el-input v-model="editForm.username" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="editForm.realName" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="editForm.phone" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="editForm.roleCode" style="width: 100%">
            <el-option v-for="role in roles" :key="role.roleCode" :label="roleName(role.roleCode)" :value="role.roleCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" required>
          <el-select v-model="editForm.status" style="width: 100%">
            <el-option label="启用" value="ENABLED" />
            <el-option label="禁用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingEdit" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import MD3Card from '@/components/MD3Card.vue';
import { assignRoleBatch, fetchAdminRoles, queryAdminUsers, updateAdminUser } from '@/api/admin';
import type { AdminRole, AdminUser, AdminUserUpdatePayload } from '@/types/admin';

const localRoleNameMap: Record<string, string> = {
  ADMIN: '管理员',
  OPERATOR: '操作员',
  AUDITOR: '审计员',
  VIEWER: '只读用户',
};

const loading = ref(false);
const records = ref<AdminUser[]>([]);
const roles = ref<AdminRole[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(20);
const keyword = ref('');
const selectedIds = ref<number[]>([]);
const selectedRole = ref('');

const editVisible = ref(false);
const savingEdit = ref(false);
const editForm = reactive<AdminUserUpdatePayload>({
  id: 0,
  username: '',
  realName: '',
  phone: '',
  roleCode: 'OPERATOR',
  status: 'ENABLED',
});

const roleLookup = computed(() => {
  return new Map(roles.value.map((role) => [role.roleCode, role.roleName]));
});

const roleName = (roleCode: string): string => {
  return localRoleNameMap[roleCode] || roleLookup.value.get(roleCode) || roleCode;
};

const displayName = (realName: string | undefined, username: string): string => {
  const normalized = (realName || '').trim();
  return normalized || username;
};

const loadData = async (): Promise<void> => {
  loading.value = true;
  try {
    const page = await queryAdminUsers({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: keyword.value || undefined,
    });
    records.value = page.records;
    total.value = page.total;
  } finally {
    loading.value = false;
  }
};

const loadRoles = async (): Promise<void> => {
  roles.value = await fetchAdminRoles();
};

const onSelectionChange = (list: AdminUser[]): void => {
  selectedIds.value = list.map((item) => item.id);
};

const onSearch = (): void => {
  pageNo.value = 1;
  void loadData();
};

const assignRole = async (): Promise<void> => {
  if (!selectedIds.value.length || !selectedRole.value) {
    return;
  }
  await assignRoleBatch({ userIds: selectedIds.value, roleCode: selectedRole.value });
  ElMessage.success('角色分配成功');
  selectedIds.value = [];
  await loadData();
};

const openEdit = (row: AdminUser): void => {
  editForm.id = row.id;
  editForm.username = row.username;
  editForm.realName = row.realName;
  editForm.phone = row.phone || '';
  editForm.roleCode = row.roleCode;
  editForm.status = row.status === 'DISABLED' ? 'DISABLED' : 'ENABLED';
  editVisible.value = true;
};

const saveEdit = async (): Promise<void> => {
  if (!editForm.username.trim() || !editForm.realName.trim()) {
    ElMessage.warning('请填写用户名和姓名');
    return;
  }

  savingEdit.value = true;
  try {
    await updateAdminUser({
      ...editForm,
      username: editForm.username.trim(),
      realName: editForm.realName.trim(),
      phone: (editForm.phone || '').trim(),
    });
    ElMessage.success('用户信息已更新');
    editVisible.value = false;
    await loadData();
  } finally {
    savingEdit.value = false;
  }
};

const toggleStatus = async (row: AdminUser): Promise<void> => {
  const nextStatus: 'ENABLED' | 'DISABLED' = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
  await updateAdminUser({
    id: row.id,
    username: row.username,
    realName: row.realName,
    phone: row.phone || '',
    roleCode: row.roleCode,
    status: nextStatus,
  });
  ElMessage.success(nextStatus === 'ENABLED' ? '用户已启用' : '用户已禁用');
  await loadData();
};

onMounted(async () => {
  await Promise.all([loadRoles(), loadData()]);
});
</script>

<style scoped>
.toolbar {
  display: grid;
  grid-template-columns: 1fr 180px auto;
  gap: 10px;
  margin-bottom: 12px;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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

