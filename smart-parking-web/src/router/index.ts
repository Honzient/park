import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router';
import { ElMessage } from 'element-plus';
import Layout from '@/layout/index.vue';
import { useAuthStore } from '@/store/auth';
import { pinia } from '@/store';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '首页', permission: 'dashboard:view' },
      },
      {
        path: 'parking',
        name: 'ParkingManagement',
        redirect: '/parking/records',
        meta: { title: '智慧停车' },
        children: [
          {
            path: 'records',
            name: 'ParkingRecords',
            component: () => import('@/views/parking/RecordList.vue'),
            meta: { title: '智慧停车 / 车辆进出记录', permission: 'parking:query' },
          },
          {
            path: 'assignment',
            name: 'ParkingAssignment',
            component: () => import('@/views/parking/SpotAssignment.vue'),
            meta: { title: '智慧停车 / 车位分配', permission: 'parking:assign' },
          },
        ],
      },
      {
        path: 'system',
        name: 'SystemAdmin',
        redirect: '/system/users',
        meta: { title: '系统管理' },
        children: [
          {
            path: 'users',
            name: 'SystemUsers',
            component: () => import('@/views/system/UserManagement.vue'),
            meta: { title: '系统管理 / 用户管理', permission: 'admin:user:view' },
          },
          {
            path: 'roles',
            name: 'SystemRoles',
            component: () => import('@/views/system/RoleManagement.vue'),
            meta: { title: '系统管理 / 角色管理', permission: 'admin:role:view' },
          },
          {
            path: 'logs',
            name: 'SystemLogs',
            component: () => import('@/views/system/LogManagement.vue'),
            meta: { title: '系统管理 / 日志管理', permission: 'admin:log:view' },
          },
        ],
      },
      {
        path: 'datacenter',
        name: 'DataCenter',
        redirect: '/datacenter/screen',
        meta: { title: '数据中心' },
        children: [
          {
            path: 'screen',
            name: 'DataCenterScreen',
            component: () => import('@/views/datacenter/Screen.vue'),
            meta: { title: '数据中心 / 统计大屏', permission: 'datacenter:query' },
          },
        ],
      },
      {
        path: 'recognition',
        name: 'VehicleRecognition',
        redirect: '/recognition/image',
        meta: { title: '车辆识别' },
        children: [
          {
            path: 'image',
            name: 'RecognitionImage',
            component: () => import('@/views/recognition/ImageRecognition.vue'),
            meta: { title: '车辆识别 / 图片识别', permission: 'recognition:image' },
          },
          {
            path: 'video',
            name: 'RecognitionVideo',
            component: () => import('@/views/recognition/VideoRecognition.vue'),
            meta: { title: '车辆识别 / 视频识别', permission: 'recognition:video' },
          },
          {
            path: 'records',
            name: 'RecognitionRecords',
            component: () => import('@/views/recognition/RecordList.vue'),
            meta: { title: '车辆识别 / 识别记录', permission: 'recognition:query' },
          },
        ],
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/profile/index.vue'),
        meta: { title: '个人中心', permission: 'profile:view' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
];

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
});

const routePriority: Array<{ path: string; permission: string }> = [
  { path: '/dashboard', permission: 'dashboard:view' },
  { path: '/parking/records', permission: 'parking:query' },
  { path: '/parking/assignment', permission: 'parking:assign' },
  { path: '/datacenter/screen', permission: 'datacenter:query' },
  { path: '/recognition/records', permission: 'recognition:query' },
  { path: '/recognition/image', permission: 'recognition:image' },
  { path: '/recognition/video', permission: 'recognition:video' },
  { path: '/system/users', permission: 'admin:user:view' },
  { path: '/system/roles', permission: 'admin:role:view' },
  { path: '/system/logs', permission: 'admin:log:view' },
  { path: '/profile', permission: 'profile:view' },
];

const firstAccessibleRoute = (authStore: ReturnType<typeof useAuthStore>): string | null => {
  const matched = routePriority.find((item) => authStore.hasPermission(item.permission));
  return matched?.path || null;
};

router.beforeEach((to, from) => {
  const authStore = useAuthStore(pinia);
  authStore.ensureValidSession();

  if (to.path === '/login') {
    if (authStore.isLoggedIn) {
      const landing = firstAccessibleRoute(authStore);
      return landing || true;
    }
    return true;
  }

  if (!authStore.isLoggedIn) {
    return '/login';
  }

  const requiredPermissions = to.matched
    .map((record) => {
      const permissionMeta = record.meta.permission;
      if (typeof permissionMeta === 'string') {
        return [permissionMeta];
      }
      if (Array.isArray(permissionMeta)) {
        return permissionMeta.filter((item): item is string => typeof item === 'string');
      }
      return [];
    })
    .flat();

  if (requiredPermissions.length > 0) {
    const allowed = requiredPermissions.every((permission) => authStore.hasPermission(permission));
    if (!allowed) {
      ElMessage.error('禁止访问：当前账号无对应权限');
      if (from.path === to.path || from.path === '/' || from.path === '/login') {
        const fallback = firstAccessibleRoute(authStore);
        return fallback || false;
      }
      return false;
    }
  }

  return true;
});

export default router;
