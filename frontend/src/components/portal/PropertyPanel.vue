<template>
  <div class="prop-panel">
    <div class="prop-panel-header">
      <span class="prop-panel-title">{{ t('design.propPanelTitle') }}</span>
      <span v-if="nodeLabel" class="prop-panel-sub muted">— {{ nodeLabel }}</span>
    </div>

    <div v-if="!node" class="prop-panel-empty muted">
      {{ t('design.propPanelEmpty') }}
    </div>

    <el-form v-else label-position="top" size="small" class="prop-panel-form">
      <el-form-item v-for="field in fields" :key="field.key" :label="field.label">
        <el-input
          v-if="field.type === 'string'"
          :model-value="getProp(field.key) as string"
          :placeholder="field.placeholder || ''"
          @update:model-value="(v) => setProp(field.key, v)"
        />
        <el-input-number
          v-else-if="field.type === 'number'"
          :model-value="getProp(field.key) as number"
          :min="0"
          controls-position="right"
          style="width: 100%"
          @update:model-value="(v) => setProp(field.key, v ?? 0)"
        />
        <el-select
          v-else-if="field.type === 'select'"
          :model-value="getProp(field.key) as string"
          style="width: 100%"
          @update:model-value="(v) => setProp(field.key, v)"
        >
          <el-option v-for="opt in (field.options || [])" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-else-if="field.type === 'textarea'"
          type="textarea"
          :rows="3"
          :model-value="getProp(field.key) as string"
          :placeholder="field.placeholder || ''"
          @update:model-value="(v) => setProp(field.key, v)"
        />
        <el-switch
          v-else-if="field.type === 'boolean'"
          :model-value="!!getProp(field.key)"
          @update:model-value="(v) => setProp(field.key, v)"
        />
        <div v-else class="muted small">unsupported field type: {{ field.type }}</div>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { LayoutNode } from './DynamicRenderer.vue';

interface BlockMeta {
  type: string;
  name: string;
  configSchema: string; // JSON
}

interface FieldDef {
  key: string;
  label: string;
  type: 'string' | 'number' | 'boolean' | 'select' | 'textarea';
  default?: any;
  placeholder?: string;
  options?: Array<{ label: string; value: string }>;
}

const props = defineProps<{
  node: LayoutNode | null;
  blockMetas: BlockMeta[];
}>();

const emit = defineEmits<{
  (e: 'update', node: LayoutNode): void;
}>();

const { t } = useI18n();

const meta = computed(() => {
  if (!props.node) return null;
  return props.blockMetas.find(b => b.type === props.node!.type) || null;
});

const fields = computed<FieldDef[]>(() => {
  if (!meta.value) return [];
  try {
    const parsed = JSON.parse(meta.value.configSchema);
    return parsed.fields || [];
  } catch { return []; }
});

const nodeLabel = computed(() => meta.value?.name || props.node?.type || '');

function getProp(key: string): unknown {
  return props.node?.props?.[key];
}

function setProp(key: string, value: unknown) {
  if (!props.node) return;
  const next: LayoutNode = {
    ...props.node,
    props: { ...(props.node.props || {}), [key]: value },
  };
  emit('update', next);
}
</script>

<style scoped>
.prop-panel {
  display: flex; flex-direction: column;
  height: 100%;
  background: var(--color-bg);
}
.prop-panel-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border-soft);
  font-size: 13px;
}
.prop-panel-title { font-weight: 600; color: var(--color-text); margin-right: 4px; }
.prop-panel-sub { font-size: 12px; }
.prop-panel-empty { padding: 24px 16px; font-size: 12px; text-align: center; }
.prop-panel-form {
  padding: 12px 16px;
  overflow-y: auto;
  flex: 1;
}
.prop-panel-form :deep(.el-form-item) { margin-bottom: 12px; }
.prop-panel-form :deep(.el-form-item__label) {
  font-size: 12px;
  color: var(--color-text-secondary);
  padding-bottom: 4px;
  line-height: 1.4;
}
</style>