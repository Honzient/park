<template>
  <el-skeleton :loading="loading" animated>
    <template #template>
      <div class="skeleton-wrapper">
        <el-skeleton-item v-for="index in rows" :key="index" variant="rect" class="skeleton-line" />
      </div>
    </template>
    <template #default>
      <slot />
    </template>
  </el-skeleton>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    loading: boolean;
    rows?: number;
  }>(),
  {
    rows: 8,
  },
);
</script>

<style scoped>
.skeleton-wrapper {
  display: grid;
  gap: 12px;
}

.skeleton-line {
  width: 100%;
  height: 36px;
}
</style>
