<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">系统管理 / 角色管理</h2>
        <p class="page-desc">菜单权限与按钮权限配置，支持精细化管理</p>
      </div>
    </div>

    <div class="role-layout">
      <MD3Card>
        <div class="role-list">
          <div
            v-for="role in roles"
            :key="role.roleCode"
            class="role-item"
            :class="{ active: role.roleCode === selectedRole }"
            @click="selectRole(role.roleCode)"
          >
            <p>{{ roleName(role.roleCode) }}</p>
            <small>{{ role.roleCode }}</small>
          </div>
        </div>
      </MD3Card>

      <MD3Card>
        <div class="perm-head">
          <h3>权限树配置</h3>
          <el-button v-has-permi="'admin:role:edit'" class="gradient-btn" :loading="saving" @click="savePermissions">
            保存配置
          </el-button>
        </div>

        <el-tree
          ref="treeRef"
          :data="permissionTree"
          node-key="key"
          show-checkbox
          default-expand-all
          :props="treeProps"
        />
      </MD3Card>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import { ElMessage, type ElTree } from 'element-plus';
import MD3Card from '@/components/MD3Card.vue';
import { fetchAdminRoles, fetchPermissionTree, updateRolePermissions } from '@/api/admin';
import { fetchCurrentUser } from '@/api/auth';
import { useAuthStore } from '@/store/auth';
import type { AdminRole, PermissionNode } from '@/types/admin';

const roleNameMap: Record<string, string> = {
  ADMIN: '管理员',
  OPERATOR: '操作员',
  AUDITOR: '审计员',
  VIEWER: '只读用户',
};

const permissionLabelMap: Record<string, string> = {
  dashboard: '\u9996\u9875',
  'dashboard:view': '\u67e5\u770b\u9996\u9875',
  'dashboard:spot:detail': '\u67e5\u770b\u8f66\u4f4d\u8be6\u60c5',
  parking: '\u667a\u6167\u505c\u8f66',
  'parking:query': '\u67e5\u770b\u8fdb\u51fa\u8bb0\u5f55',
  'parking:assign': '\u8f66\u4f4d\u5206\u914d',
  recognition: '\u8f66\u8f86\u8bc6\u522b',
  'recognition:query': '\u67e5\u770b\u8bc6\u522b\u8bb0\u5f55',
  'recognition:image': '\u56fe\u7247\u8bc6\u522b',
  'recognition:video': '\u89c6\u9891\u8bc6\u522b',
  'recognition:export': '\u5bfc\u51fa\u8bc6\u522b\u8bb0\u5f55',
  datacenter: '\u6570\u636e\u4e2d\u5fc3',
  'datacenter:query': '\u67e5\u770b\u6570\u636e\u4e2d\u5fc3',
  'datacenter:export:excel': '\u5bfc\u51fa Excel',
  'datacenter:export:pdf': '\u5bfc\u51fa PDF',
  admin: '\u7cfb\u7edf\u7ba1\u7406',
  'admin:user:view': '\u7528\u6237\u7ba1\u7406',
  'admin:user:assign-role': '\u6279\u91cf\u5206\u914d\u89d2\u8272',
  'admin:role:view': '\u89d2\u8272\u7ba1\u7406',
  'admin:role:edit': '\u89d2\u8272\u6743\u9650\u7f16\u8f91',
  'admin:log:view': '\u65e5\u5fd7\u67e5\u770b',
  profile: '\u4e2a\u4eba\u4e2d\u5fc3',
  'profile:view': '\u67e5\u770b\u4e2a\u4eba\u4fe1\u606f',
  'profile:edit': '\u7f16\u8f91\u4e2a\u4eba\u4fe1\u606f',
  'profile:password': '\u4fee\u6539\u5bc6\u7801',
};

const roles = ref<AdminRole[]>([]);
const permissionTree = ref<PermissionNode[]>([]);
const selectedRole = ref('');
const saving = ref(false);
const treeRef = ref<InstanceType<typeof ElTree>>();
const authStore = useAuthStore();

const treeProps = {
  label: 'label',
  children: 'children',
};

const roleName = (roleCode: string): string => {
  return roleNameMap[roleCode] || roleCode;
};

const normalizePermissions = (nodes: PermissionNode[]): PermissionNode[] => {
  return nodes.map((node) => ({
    key: node.key,
    label: permissionLabelMap[node.key] || node.label,
    children: normalizePermissions(node.children || []),
  }));
};

const permissionKeySet = computed(() => {
  const set = new Set<string>();
  const collect = (nodes: PermissionNode[]): void => {
    nodes.forEach((node) => {
      if (node.key.includes(':')) {
        set.add(node.key);
      }
      if (node.children?.length) {
        collect(node.children);
      }
    });
  };
  collect(permissionTree.value);
  return set;
});

const activeRole = (): AdminRole | undefined => {
  return roles.value.find((item) => item.roleCode === selectedRole.value);
};

const applyRolePermissions = async (): Promise<void> => {
  const role = activeRole();
  if (!role || !treeRef.value) {
    return;
  }
  treeRef.value.setCheckedKeys(role.permissions, false);
  await nextTick();
};

const selectRole = (roleCode: string): void => {
  selectedRole.value = roleCode;
  void applyRolePermissions();
};

const loadData = async (): Promise<void> => {
  const [roleData, treeData] = await Promise.all([fetchAdminRoles(), fetchPermissionTree()]);
  roles.value = roleData;
  permissionTree.value = normalizePermissions(treeData);
  if (!selectedRole.value && roles.value.length > 0) {
    selectedRole.value = roles.value[0]?.roleCode || '';
  }
  await applyRolePermissions();
};

const savePermissions = async (): Promise<void> => {
  if (!selectedRole.value || !treeRef.value) {
    return;
  }

  saving.value = true;
  try {
    const checkedKeys = treeRef.value.getCheckedKeys(false) as string[];
    const halfCheckedKeys = treeRef.value.getHalfCheckedKeys() as string[];
    const allSelectedKeys = [...checkedKeys, ...halfCheckedKeys];
    const permissions = [...new Set(allSelectedKeys.filter((key) => permissionKeySet.value.has(key)))];

    await updateRolePermissions({
      roleCode: selectedRole.value,
      permissions,
    });

    const currentUser = await fetchCurrentUser();
    authStore.syncCurrentUser({
      username: currentUser.username,
      roleCode: currentUser.roleCode,
      permissions: currentUser.permissions,
    });

    ElMessage.success('角色权限已更新');
    await loadData();
  } finally {
    saving.value = false;
  }
};

onMounted(() => {
  void loadData();
});
</script>

<style scoped>
.role-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 12px;
}

.role-list {
  display: grid;
  gap: 10px;
}

.role-item {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 12px;
  cursor: pointer;
  background: #f8f9ff;
}

.role-item.active {
  border-color: var(--primary);
  box-shadow: 0 0 0 1px rgba(67, 97, 238, 0.2);
  background: #eef2ff;
}

.role-item p {
  margin: 0;
  font-weight: 600;
}

.role-item small {
  color: var(--text-sub);
}

.perm-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.perm-head h3 {
  margin: 0;
}

@media (max-width: 992px) {
  .role-layout {
    grid-template-columns: 1fr;
  }
}
</style>
