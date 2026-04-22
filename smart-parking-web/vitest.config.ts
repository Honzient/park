import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
const frontendTestsRoot = fileURLToPath(new URL('../tests/e2e/frontend', import.meta.url));
const frontendMocksRoot = fileURLToPath(new URL('../tests/e2e/frontend/mocks', import.meta.url));
const axiosEntry = fileURLToPath(new URL('./node_modules/axios/index.js', import.meta.url));
const piniaEntry = fileURLToPath(new URL('./node_modules/pinia/dist/pinia.mjs', import.meta.url));
const vueTestUtilsEntry = fileURLToPath(new URL('./node_modules/@vue/test-utils/dist/vue-test-utils.esm-bundler.mjs', import.meta.url));

export default defineConfig({
  plugins: [vue()],
  server: {
    fs: {
      allow: [repoRoot],
    },
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      axios: axiosEntry,
      echarts: `${frontendMocksRoot.replace(/\\/g, '/')}/echarts.ts`,
      pinia: piniaEntry,
      '@vue/test-utils': vueTestUtilsEntry,
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    include: [`${frontendTestsRoot.replace(/\\/g, '/')}/**/*.test.ts`],
    setupFiles: [fileURLToPath(new URL('../tests/e2e/frontend/setup.ts', import.meta.url))],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      reportsDirectory: '../tests/reports/frontend-coverage',
    },
  },
});