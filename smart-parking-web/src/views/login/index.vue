<template>
  <div class="login-page">
    <div class="backdrop"></div>
    <el-card class="login-card" shadow="never">
      <div class="title-block">
        <h2>智慧停车场管理系统</h2>
        <p>基于 Vue3 + Spring Boot3 + WebSocket + JWT</p>
      </div>

      <el-alert
        v-if="captchaError"
        type="warning"
        :closable="false"
        class="captcha-alert"
        :title="captchaError"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" placeholder="请输入账号" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入强密码" />
        </el-form-item>

        <el-form-item label="图形验证码" prop="captchaCode">
          <div class="captcha-row">
            <el-input v-model="form.captchaCode" placeholder="输入验证码" />
            <img
              v-if="captchaImage"
              :src="captchaImage"
              class="captcha-img"
              alt="验证码"
              @click="refreshCaptcha"
            />
            <button v-else type="button" class="captcha-placeholder" @click="refreshCaptcha">
              点击重试
            </button>
          </div>
        </el-form-item>

        <el-button class="gradient-btn login-btn" :loading="loading" :disabled="!captchaReady" @click="submit">登录</el-button>
      </el-form>

      <div class="tip-block">
        <p>管理员：admin / Admin1234</p>
        <p>操作员：operator / Operator123</p>
        <p>密码强度要求：至少8位，含大小写字母和数字</p>
        <p>登录失败3次将锁定30分钟</p>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { fetchCaptcha, login } from '@/api/auth';
import { useAuthStore } from '@/store/auth';

const router = useRouter();
const authStore = useAuthStore();

const loading = ref(false);
const formRef = ref<FormInstance>();
const captchaImage = ref('');
const captchaReady = ref(false);
const captchaError = ref('');

const form = reactive({
  username: 'admin',
  password: 'Admin1234',
  captchaId: '',
  captchaCode: '',
});

const strongPasswordReg = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/;

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (!strongPasswordReg.test(String(value || ''))) {
          callback(new Error('密码需至少8位，含大小写字母和数字'));
          return;
        }
        callback();
      },
      trigger: 'blur',
    },
  ],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
};

const refreshCaptcha = async (): Promise<boolean> => {
  try {
    const captcha = await fetchCaptcha();
    form.captchaId = captcha.captchaId;
    form.captchaCode = '';
    captchaImage.value = captcha.imageBase64;
    captchaReady.value = true;
    captchaError.value = '';
    return true;
  } catch {
    form.captchaId = '';
    form.captchaCode = '';
    captchaImage.value = '';
    captchaReady.value = false;
    captchaError.value = '验证码加载失败，请启动后端服务并检查代理端口（默认 8080）。';
    return false;
  }
};

const submit = async (): Promise<void> => {
  if (!captchaReady.value || !form.captchaId) {
    ElMessage.warning('验证码未就绪，请先重试加载验证码');
    await refreshCaptcha();
    return;
  }

  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }

  loading.value = true;
  try {
    const response = await login({
      username: form.username,
      password: form.password,
      captchaId: form.captchaId,
      captchaCode: form.captchaCode,
    });
    authStore.setAuth(response);
    ElMessage.success('登录成功');
    await router.push('/dashboard');
  } catch {
    await refreshCaptcha();
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  void refreshCaptcha();
});
</script>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: grid;
  place-items: center;
  overflow: hidden;
}

.backdrop {
  position: absolute;
  inset: 0;
  background: #f7f8fb;
}

.login-card {
  position: relative;
  width: min(440px, 92vw);
  border-radius: 16px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-10);
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.title-block h2 {
  margin: 0;
}

.title-block p {
  margin: 6px 0 16px;
  color: var(--text-sub);
  font-size: 13px;
}

.captcha-alert {
  margin-bottom: 12px;
}

.captcha-row {
  display: grid;
  grid-template-columns: 1fr 132px;
  gap: 10px;
}

.captcha-img {
  width: 132px;
  height: 40px;
  border: 1px solid var(--border);
  border-radius: 10px;
  cursor: pointer;
}

.captcha-placeholder {
  width: 132px;
  height: 40px;
  border: 1px dashed var(--border);
  border-radius: 10px;
  background: #fff;
  color: var(--text-sub);
  cursor: pointer;
}

.login-btn {
  width: 100%;
  margin-top: 6px;
}

.tip-block {
  margin-top: 12px;
  color: var(--text-sub);
  font-size: 12px;
}

.tip-block p {
  margin: 4px 0;
}
</style>

