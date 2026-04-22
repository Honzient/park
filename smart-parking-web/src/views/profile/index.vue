<template>
  <section class="page-shell">
    <div class="page-header">
      <div>
        <h2 class="page-title">个人中心</h2>
        <p class="page-desc">维护个人信息、密码与登录记录</p>
      </div>
    </div>

    <div class="profile-grid">
      <MD3Card>
        <h3 class="block-title">个人信息</h3>
        <el-form label-position="top" :model="profileForm">
          <el-form-item label="用户名">
            <el-input v-model="profileForm.username" />
          </el-form-item>
          <el-form-item label="姓名">
            <el-input v-model="profileForm.realName" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="profileForm.phone" />
          </el-form-item>
          <el-form-item label="角色">
            <el-input :model-value="formatRole(profileForm.roleCode)" disabled />
          </el-form-item>
          <el-form-item label="最后登录时间">
            <el-input v-model="profileForm.lastLoginTime" disabled />
          </el-form-item>
          <el-button v-has-permi="'profile:edit'" class="gradient-btn" :loading="savingProfile" @click="saveProfile">
            保存信息
          </el-button>
        </el-form>
      </MD3Card>

      <MD3Card>
        <h3 class="block-title">修改密码</h3>
        <el-form ref="passwordFormRef" label-position="top" :model="passwordForm" :rules="passwordRules">
          <el-form-item label="旧密码" prop="oldPassword">
            <el-input v-model="passwordForm.oldPassword" type="password" show-password />
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input v-model="passwordForm.newPassword" type="password" show-password />
          </el-form-item>
          <el-button v-has-permi="'profile:password'" class="gradient-btn" :loading="savingPassword" @click="savePassword">
            更新密码
          </el-button>
        </el-form>
      </MD3Card>
    </div>

    <MD3Card>
      <h3 class="block-title">个人登录记录</h3>
      <el-table v-loading="loadingLogs" :data="loginLogs" table-layout="fixed" stripe>
        <el-table-column prop="loginTime" label="时间" min-width="180" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" min-width="140" show-overflow-tooltip />
        <el-table-column prop="device" label="设备" min-width="200" show-overflow-tooltip />
        <el-table-column prop="loginStatus" label="状态" min-width="120">
          <template #default="scope">
            <el-tag :type="scope.row.loginStatus === 'SUCCESS' ? 'success' : 'danger'">{{ scope.row.loginStatus === 'SUCCESS' ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="logPageNo"
          v-model:page-size="logPageSize"
          :page-sizes="[10, 20, 50, 100]"
          background
          layout="total, sizes, prev, pager, next"
          :total="logTotal"
          @size-change="loadLogs"
          @current-change="loadLogs"
        />
      </div>
    </MD3Card>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import MD3Card from '@/components/MD3Card.vue';
import { changePassword, fetchProfile, queryProfileLoginLogs, updateProfile } from '@/api/profile';
import type { LoginLog } from '@/types/admin';
import type { ProfileInfo } from '@/types/profile';
import { useAuthStore } from '@/store/auth';

const passwordReg = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/;

const router = useRouter();
const authStore = useAuthStore();

const roleNameMap: Record<string, string> = {
  ADMIN: '管理员',
  OPERATOR: '操作员',
  AUDITOR: '审计员',
  VIEWER: '只读用户',
};

const profileForm = reactive<ProfileInfo>({
  username: '',
  realName: '',
  phone: '',
  roleCode: '',
  lastLoginTime: '',
});

const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
});

const passwordFormRef = ref<FormInstance>();

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (!passwordReg.test(String(value || ''))) {
          callback(new Error('密码至少8位，且包含大小写字母和数字'));
          return;
        }
        callback();
      },
      trigger: 'blur',
    },
  ],
};

const savingProfile = ref(false);
const savingPassword = ref(false);

const loadingLogs = ref(false);
const loginLogs = ref<LoginLog[]>([]);
const logPageNo = ref(1);
const logPageSize = ref(20);
const logTotal = ref(0);

const formatRole = (roleCode: string): string => {
  return roleNameMap[roleCode] || roleCode;
};

const loadProfile = async (): Promise<void> => {
  const data = await fetchProfile();
  Object.assign(profileForm, data);
};

const loadLogs = async (): Promise<void> => {
  loadingLogs.value = true;
  try {
    const page = await queryProfileLoginLogs({
      pageNo: logPageNo.value,
      pageSize: logPageSize.value,
    });
    loginLogs.value = page.records;
    logTotal.value = page.total;
  } finally {
    loadingLogs.value = false;
  }
};

const saveProfile = async (): Promise<void> => {
  const targetUsername = profileForm.username.trim();
  savingProfile.value = true;
  try {
    await updateProfile({ username: targetUsername, realName: profileForm.realName, phone: profileForm.phone });
    if (targetUsername !== authStore.username) {
      ElMessage.success('用户名已更新，请重新登录');
      authStore.clearAuth();
      await router.replace('/login');
      return;
    }
    ElMessage.success('个人信息已更新');
    await loadProfile();
  } finally {
    savingProfile.value = false;
  }
};

const savePassword = async (): Promise<void> => {
  const valid = await passwordFormRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }

  savingPassword.value = true;
  try {
    await changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword,
    });
    ElMessage.success('密码已更新，请重新登录');
    passwordForm.oldPassword = '';
    passwordForm.newPassword = '';
  } finally {
    savingPassword.value = false;
  }
};

onMounted(async () => {
  await Promise.all([loadProfile(), loadLogs()]);
});
</script>

<style scoped>
.profile-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.block-title {
  margin: 0 0 10px;
}

.pagination-wrap {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 992px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>

