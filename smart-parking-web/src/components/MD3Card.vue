<template>
  <el-card class="md3-card" shadow="never">
    <slot />
  </el-card>
</template>

<style scoped>
.md3-card {
  border-radius: 14px;
  border: 1px solid rgba(214, 221, 237, 0.9);
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 8px 24px rgba(45, 70, 130, 0.08);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.md3-card:hover {
  box-shadow: 0 10px 28px rgba(45, 70, 130, 0.1);
}
</style>
