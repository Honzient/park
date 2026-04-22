<template>
  <div class="field-grid">
    <div
      v-for="field in orderedFields"
      :key="field.key"
      class="field-item"
      draggable="true"
      @dragstart="onDragStart(field.key)"
      @dragover.prevent
      @drop="onDrop(field.key)"
    >
      <div class="field-label">
        <el-icon><Rank /></el-icon>
        <span>{{ field.label }}</span>
      </div>
      <div class="field-content">
        <slot :name="field.key" :field="field" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';

interface QueryField {
  key: string;
  label: string;
}

const props = defineProps<{
  modelValue: string[];
  fields: QueryField[];
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: string[]): void;
}>();

const draggingKey = ref('');

const orderedFields = computed(() => {
  const lookup = new Map(props.fields.map((field) => [field.key, field]));
  const ordered: QueryField[] = [];

  props.modelValue.forEach((key) => {
    const item = lookup.get(key);
    if (item) {
      ordered.push(item);
      lookup.delete(key);
    }
  });

  lookup.forEach((value) => ordered.push(value));
  return ordered;
});

const onDragStart = (key: string): void => {
  draggingKey.value = key;
};

const onDrop = (targetKey: string): void => {
  if (!draggingKey.value || draggingKey.value === targetKey) {
    return;
  }

  const order = orderedFields.value.map((field) => field.key);
  const sourceIndex = order.indexOf(draggingKey.value);
  const targetIndex = order.indexOf(targetKey);
  if (sourceIndex === -1 || targetIndex === -1) {
    return;
  }

  const nextOrder = [...order];
  const moved = nextOrder.splice(sourceIndex, 1)[0];
  if (!moved) {
    return;
  }
  nextOrder.splice(targetIndex, 0, moved);
  emit('update:modelValue', nextOrder);
};
</script>

<style scoped>
.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}

.field-item {
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--md3-outline-soft);
  border-radius: 12px;
  padding: 10px 12px;
}

.field-label {
  font-size: 12px;
  color: var(--md3-text-secondary);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
  cursor: move;
}

.field-content {
  min-height: 40px;
}
</style>
