import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import App from './App.vue';
import router from './router';
import { pinia } from './store';
import { hasPermiDirective } from './directives/hasPermi';
import './style.css';

const app = createApp(App);

app.config.errorHandler = (error, _instance, info) => {
  console.error('[Vue Error]', info, error);
};

window.addEventListener('error', (event) => {
  console.error('[Window Error]', event.error || event.message);
});

window.addEventListener('unhandledrejection', (event) => {
  console.error('[Unhandled Rejection]', event.reason);
});

Object.entries(ElementPlusIconsVue).forEach(([key, component]) => {
  app.component(key, component);
});

app.directive('has-permi', hasPermiDirective);
app.use(pinia);
app.use(router);
app.use(ElementPlus, { locale: zhCn });
app.mount('#app');
