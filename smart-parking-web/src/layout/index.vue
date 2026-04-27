<template>
  <el-container class="layout-shell">
    <el-aside v-if="!isMobile" width="268px" class="sidebar">
      <div class="brand">
        <div class="brand-badge">SP</div>
        <div>
          <h1>智慧停车场管理系统</h1>
          <p>智慧停车平台</p>
        </div>
      </div>

      <el-menu :default-active="$route.path" class="menu" router unique-opened>
        <el-menu-item index="/dashboard">
          <el-icon><DataBoard /></el-icon>
          <span>首页</span>
        </el-menu-item>

        <el-sub-menu index="parking">
          <template #title>
            <el-icon><Van /></el-icon>
            <span>智慧停车</span>
          </template>
          <el-menu-item index="/parking/records">车辆进出记录</el-menu-item>
          <el-menu-item index="/parking/assignment">车位分配</el-menu-item>
        </el-sub-menu>

        <el-sub-menu index="system">
          <template #title>
            <el-icon><Setting /></el-icon>
            <span>系统管理</span>
          </template>
          <el-menu-item index="/system/users">用户管理</el-menu-item>
          <el-menu-item index="/system/roles">角色管理</el-menu-item>
          <el-menu-item index="/system/logs">日志管理</el-menu-item>
        </el-sub-menu>

        <el-sub-menu index="datacenter">
          <template #title>
            <el-icon><PieChart /></el-icon>
            <span>数据中心</span>
          </template>
          <el-menu-item index="/datacenter/screen">统计大屏</el-menu-item>
        </el-sub-menu>

        <el-sub-menu index="recognition">
          <template #title>
            <el-icon><Camera /></el-icon>
            <span>车辆识别</span>
          </template>
          <el-menu-item index="/recognition/image">图片识别</el-menu-item>
          <el-menu-item index="/recognition/video">视频识别</el-menu-item>
          <el-menu-item index="/recognition/records">识别记录</el-menu-item>
        </el-sub-menu>

        <el-menu-item index="/assistant">
          <el-icon><ChatLineRound /></el-icon>
          <span>智能助手</span>
        </el-menu-item>
        <el-menu-item index="/profile">
          <el-icon><User /></el-icon>
          <span>个人中心</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-left">
          <el-button v-if="isMobile" text @click="mobileMenuVisible = true">
            <el-icon><Menu /></el-icon>
          </el-button>
          <div>
            <h3>{{ $route.meta.title || '智慧停车场管理系统' }}</h3>
            <p>{{ currentDate }}</p>
          </div>
        </div>

        <div class="topbar-right">
          <el-tag type="success" effect="dark">{{ authStore.username }}</el-tag>
          <el-button class="gradient-btn" @click="logout">退出登录</el-button>
        </div>
      </el-header>

      <el-main class="main-panel fade-up">
        <router-view />
      </el-main>
    </el-container>

    <el-drawer v-model="mobileMenuVisible" direction="ltr" size="280px" title="菜单导航">
      <el-menu :default-active="$route.path" router @select="mobileMenuVisible = false">
        <el-menu-item index="/dashboard">首页</el-menu-item>
        <el-menu-item index="/parking/records">智慧停车 / 车辆进出记录</el-menu-item>
        <el-menu-item index="/parking/assignment">智慧停车 / 车位分配</el-menu-item>
        <el-menu-item index="/system/users">系统管理 / 用户管理</el-menu-item>
        <el-menu-item index="/system/roles">系统管理 / 角色管理</el-menu-item>
        <el-menu-item index="/system/logs">系统管理 / 日志管理</el-menu-item>
        <el-menu-item index="/datacenter/screen">数据中心 / 统计大屏</el-menu-item>
        <el-menu-item index="/recognition/image">车辆识别 / 图片识别</el-menu-item>
        <el-menu-item index="/recognition/video">车辆识别 / 视频识别</el-menu-item>
        <el-menu-item index="/recognition/records">车辆识别 / 识别记录</el-menu-item>
        <el-menu-item index="/assistant">智能助手</el-menu-item>
        <el-menu-item index="/profile">个人中心</el-menu-item>
      </el-menu>
    </el-drawer>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/auth';

const authStore = useAuthStore();
const router = useRouter();

const mobileMenuVisible = ref(false);
const isMobile = ref(window.innerWidth <= 1100);

const currentDate = computed(() => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
});

const handleResize = (): void => {
  isMobile.value = window.innerWidth <= 1100;
  if (!isMobile.value) {
    mobileMenuVisible.value = false;
  }
};

const logout = (): void => {
  authStore.clearAuth();
  router.push('/login');
};

onMounted(() => {
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize);
});
</script>

<style scoped>
.layout-shell {
  min-height: 100vh;
}

.sidebar {
  border-right: 1px solid var(--border);
  background: #fbfcfe;
}

.brand {
  height: 84px;
  padding: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid var(--border);
}

.brand-badge {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  color: #fff;
  display: grid;
  place-items: center;
  font-weight: 700;
  background: var(--primary);
}

.brand h1 {
  margin: 0;
  font-size: 14px;
}

.brand p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-sub);
}

.menu {
  border-right: 0;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.85);
  border-bottom: 1px solid var(--border);
  backdrop-filter: blur(8px);
}

.topbar-left,
.topbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.topbar-left h3 {
  margin: 0;
  font-size: 16px;
}

.topbar-left p {
  margin: 2px 0 0;
  color: var(--text-sub);
  font-size: 12px;
}

.main-panel {
  padding: 18px;
}
</style>


