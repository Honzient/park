import { fileURLToPath, URL } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

const toWsTarget = (apiTarget: string): string => {
  if (apiTarget.startsWith('ws://') || apiTarget.startsWith('wss://')) {
    return apiTarget;
  }
  if (apiTarget.startsWith('https://')) {
    return `wss://${apiTarget.slice('https://'.length)}`;
  }
  if (apiTarget.startsWith('http://')) {
    return `ws://${apiTarget.slice('http://'.length)}`;
  }
  return `ws://${apiTarget}`;
};

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_PROXY_API_TARGET?.trim() || 'http://127.0.0.1:8080';
  const wsTarget = env.VITE_PROXY_WS_TARGET?.trim() || toWsTarget(apiTarget);

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 3000,
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/ws': {
          target: wsTarget,
          ws: true,
        },
      },
    },
  };
});
