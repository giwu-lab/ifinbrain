<script setup lang="ts">
/**
 * 报表查询头。
 *
 * 按 sys_query.params_schema 动态生成表单控件 —— 类型只有 string/number/date/datetime
 * 四种（与后端 QueryParamSchema 一致，不擅自扩展：扩展会让前后端类型转换对不上）。
 *
 * 设计器与播放页共用本组件，保证「设计时看到的头」与「跑起来用的头」是同一套渲染。
 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { ArrowDown, ArrowUp } from '@element-plus/icons-vue';
import type { ReportParamSchema } from '@/api/report';

const props = withDefaults(defineProps<{
  params: ReportParamSchema[];
  modelValue: Record<string, string>;
  /** 每行字段数 */
  columns?: number;
  /** 折叠时只显示前 4 个 */
  collapsed?: boolean;
  loading?: boolean;
  disabled?: boolean;
}>(), {
  columns: 4,
  collapsed: false,
  loading: false,
  disabled: false,
});

const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, string>): void;
  (e: 'search'): void;
  (e: 'reset'): void;
  (e: 'toggle-collapse'): void;
}>();

const { t } = useI18n();

/** el-col 的 span = 24 / columns */
const span = computed(() => Math.max(1, Math.min(12, Math.floor(24 / (props.columns || 4)))));

const shown = computed(() =>
  props.collapsed ? props.params.slice(0, 4) : props.params
);

const canCollapse = computed(() => props.params.length > 4);

function setValue(name: string, v: string | number | Date | null | undefined) {
  // el-date-picker 在清空时给 null；统一落成空串，避免把 null 塞进请求体
  const val = v === null || v === undefined ? '' : String(v);
  emit('update:modelValue', { ...props.modelValue, [name]: val });
}

function onEnter(e: Event) {
  if ((e as KeyboardEvent).key === 'Enter') emit('search');
}
</script>

<template>
  <div class="rp-header" :class="{ collapsed }">
    <el-form label-position="top" class="rp-header-form" @submit.prevent>
      <el-row :gutter="16">
        <el-col v-for="p in shown" :key="p.name" :xs="24" :sm="12" :md="span * 2">
          <el-form-item :label="p.label || p.name" :required="p.required">
            <el-input-number
              v-if="p.type === 'number'"
              :model-value="modelValue[p.name] === '' || modelValue[p.name] == null ? undefined : Number(modelValue[p.name])"
              :placeholder="p.placeholder"
              :disabled="disabled"
              controls-position="right"
              style="width: 100%"
              @update:model-value="(v: any) => setValue(p.name, v)"
              @keyup.enter="onEnter"
            />
            <el-date-picker
              v-else-if="p.type === 'date' || p.type === 'datetime'"
              :model-value="modelValue[p.name] || null"
              :type="p.type === 'date' ? 'date' : 'datetime'"
              :value-format="p.type === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss'"
              :placeholder="p.placeholder || t('report.pickDate')"
              :disabled="disabled"
              style="width: 100%"
              @update:model-value="(v: any) => setValue(p.name, v)"
            />
            <el-input
              v-else
              :model-value="modelValue[p.name] ?? ''"
              :placeholder="p.placeholder"
              :disabled="disabled"
              clearable
              @update:model-value="(v: string) => setValue(p.name, v)"
              @keyup.enter="onEnter"
            />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <div class="rp-header-bar">
      <div class="rp-header-ops">
        <el-button type="primary" :loading="loading" :disabled="disabled" @click="emit('search')">
          {{ t('report.query') }}
        </el-button>
        <el-button :disabled="disabled || loading" @click="emit('reset')">
          {{ t('common.reset') }}
        </el-button>
        <el-button
          v-if="canCollapse"
          link
          type="primary"
          :icon="collapsed ? ArrowDown : ArrowUp"
          @click="emit('toggle-collapse')"
        >
          {{ collapsed ? t('report.expand') : t('report.collapse') }}
        </el-button>
      </div>
      <div v-if="$slots.extra" class="rp-header-extra">
        <slot name="extra" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.rp-header {
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-bg-elev);
  padding: 14px 16px 6px;
}

.rp-header-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.rp-header-form :deep(.el-form-item__label) {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  padding-bottom: 4px;
}

.rp-header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 0 8px;
}

.rp-header-ops {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
