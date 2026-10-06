<script setup lang="ts">
/**
 * 组件外壳：标题栏 + 内容区 + 加载/错误态 + 设计态的选中框与缩放手柄。
 *
 * 交互事件一律 emit 给父级 BiCanvas 处理 —— 本组件不直接改 store，
 * 保证「一次手势 = 一次 commit」的撤销粒度。
 */
import { computed } from 'vue';
import dayjs from 'dayjs';
import type { BiDataResp, BiWidget, DashboardTheme } from '@/api/bi';
import { useI18n } from 'vue-i18n';
import { Loading, WarningFilled } from '@element-plus/icons-vue';
import BiWidgetBody from './BiWidgetBody.vue';

const props = withDefaults(defineProps<{
  widget: BiWidget;
  data: BiDataResp | null;
  theme: DashboardTheme;
  selected?: boolean;
  editable?: boolean;
  loading?: boolean;
  error?: string;
  lastOkAt?: number;
  zIndex?: number;
  left?: number;
  top?: number;
  width?: number;
  height?: number;
}>(), {
  selected: false,
  editable: false,
  loading: false,
  error: '',
  lastOkAt: 0,
  zIndex: 0,
  left: 0,
  top: 0,
  width: 0,
  height: 0,
});

const emit = defineEmits<{
  (e: 'select', id: string): void;
  (e: 'pointerdown-body', ev: PointerEvent, id: string): void;
  (e: 'pointerdown-handle', ev: PointerEvent, id: string, dir: string): void;
}>();

const { t } = useI18n();

const HANDLES = ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'];

const boxStyle = computed(() => ({
  left: `${props.left}px`,
  top: `${props.top}px`,
  width: `${props.width}px`,
  height: `${props.height}px`,
  zIndex: props.zIndex,
  display: props.widget.hidden ? 'none' : undefined,
}));

const panelStyle = computed(() => {
  const s = props.widget.style;
  return {
    background: s.transparent ? 'transparent' : s.background,
    border: `1px solid ${s.borderColor}`,
    borderRadius: `${s.radius}px`,
  };
});

const lastOkText = computed(() =>
  props.lastOkAt ? dayjs(props.lastOkAt).format('HH:mm:ss') : '');
</script>

<template>
  <div
    class="bi-widget"
    :class="{ 'is-selected': selected && editable, 'is-locked': widget.locked }"
    :style="boxStyle"
    @pointerdown.stop="emit('select', widget.id)"
  >
    <div class="panel" :style="panelStyle" @pointerdown="editable && !widget.locked && emit('pointerdown-body', $event, widget.id)">
      <div v-if="widget.style.titleVisible" class="widget-head">
        <span class="head-bar"></span>
        <span class="head-title">{{ widget.style.title }}</span>
        <span v-if="data?.cached && editable" class="head-cache tabular">
          {{ t('bi.view.cached', { n: Math.round(data.cacheAgeMs / 1000) }) }}
        </span>
      </div>

      <div class="widget-body" :style="{ padding: `${widget.style.padding}px` }">
        <BiWidgetBody :widget="widget" :data="data" :theme="theme" />
      </div>

      <!-- 加载：只在没有任何数据时遮罩，避免刷新时闪白 -->
      <div v-if="loading && !data?.rows?.length" class="mask">
        <el-icon class="is-loading"><Loading /></el-icon>
      </div>
      <!-- 失败降级：保留上一帧画面，只叠提示，不清空内容 -->
      <div v-if="error" class="err-bar">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ t('bi.view.staleFailed') }}</span>
        <span v-if="lastOkText" class="tabular">{{ lastOkText }}</span>
      </div>
      <div v-else-if="error === '' && !data?.rows?.length && !loading && widget.data?.queryId" class="mask empty-mask">
        {{ t('bi.view.noData') }}
      </div>

      <!-- 设计态：缩放手柄 -->
      <template v-if="editable && selected && !widget.locked">
        <span
          v-for="d in HANDLES"
          :key="d"
          class="handle"
          :class="`h-${d}`"
          @pointerdown.stop="emit('pointerdown-handle', $event, widget.id, d)"
        ></span>
      </template>
    </div>
  </div>
</template>

<style scoped>
.bi-widget {
  position: absolute;
  box-sizing: border-box;
}
.panel {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  overflow: hidden;
}
.is-selected .panel {
  outline: 2px solid var(--color-primary, #2563eb);
  outline-offset: 1px;
}
.widget-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px 4px;
  flex: 0 0 auto;
}
.head-bar {
  width: 3px;
  height: 12px;
  background: var(--bi-accent);
  border-radius: 2px;
  flex: 0 0 3px;
}
.head-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--bi-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.head-cache {
  margin-left: auto;
  font-size: 11px;
  color: var(--bi-text-muted);
}
.widget-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}
.mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--bi-panel) 72%, transparent);
  color: var(--bi-text-muted);
}
.err-bar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  font-size: 11px;
  color: var(--bi-text-secondary);
  background: color-mix(in srgb, var(--bi-panel) 88%, transparent);
  border-top: 1px solid var(--bi-border);
}
.handle {
  position: absolute;
  width: 9px;
  height: 9px;
  background: var(--color-primary, #2563eb);
  border: 1px solid var(--bi-panel, #fff);
  border-radius: 2px;
  z-index: 2;
}
.h-n, .h-s { left: 50%; margin-left: -4.5px; }
.h-e, .h-w { top: 50%; margin-top: -4.5px; }
.h-n { top: -5px; cursor: ns-resize; }
.h-s { bottom: -5px; cursor: ns-resize; }
.h-w { left: -5px; cursor: ew-resize; }
.h-e { right: -5px; cursor: ew-resize; }
.h-nw { top: -5px; left: -5px; cursor: nwse-resize; }
.h-ne { top: -5px; right: -5px; cursor: nesw-resize; }
.h-sw { bottom: -5px; left: -5px; cursor: nesw-resize; }
.h-se { bottom: -5px; right: -5px; cursor: nwse-resize; }
</style>