<template>
  <el-drawer
    :model-value="modelValue"
    direction="rtl"
    size="340px"
    :with-header="false"
    :modal="true"
    :modal-class="'sp-modal'"
    @update:model-value="(v) => $emit('update:modelValue', v)"
  >
    <div class="sp-root">
      <!-- header -->
      <header class="sp-header">
        <h3 class="sp-title">{{ t('stylePanel.title') }}</h3>
        <el-button text :icon="Close" class="sp-close" @click="$emit('update:modelValue', false)" />
      </header>

      <el-tabs v-model="activeTab" class="sp-tabs" stretch>
        <el-tab-pane :label="t('stylePanel.tabTheme')" name="theme">
          <!-- 界面布局 -->
          <StylePanelLayout
            :title="t('stylePanel.layout')"
            :model-value="s.layout"
            @update:model-value="(v) => update({ layout: v })"
          />

          <!-- 系统主题（主色） -->
          <StylePanelColorPicker
            :title="t('stylePanel.systemTheme')"
            :color="s.primaryColor"
            :opacity="s.primaryOpacity"
            :presets="PRIMARY_PRESETS"
            @update:color="(v) => update({ primaryColor: v })"
            @update:opacity="(v) => update({ primaryOpacity: v })"
          />

          <!-- 顶栏主题 -->
          <StylePanelColorPicker
            :title="t('stylePanel.headerTheme')"
            :color="s.headerColor"
            :opacity="s.headerOpacity"
            :presets="HEADER_PRESETS"
            @update:color="(v) => update({ headerColor: v })"
            @update:opacity="(v) => update({ headerOpacity: v })"
          />

          <!-- 菜单主题 -->
          <StylePanelColorPicker
            :title="t('stylePanel.menuTheme')"
            :color="s.menuColor"
            :opacity="s.menuOpacity"
            :presets="MENU_PRESETS"
            @update:color="(v) => update({ menuColor: v })"
            @update:opacity="(v) => update({ menuOpacity: v })"
          />

          <!-- 动画 / 进度条 / loading -->
          <StylePanelAnimation
            :enable-animation="s.enableAnimation"
            :animation-type="s.animationType"
            :enable-progress="s.enableProgress"
            :enable-loading="s.enableLoading"
            @update:enable-animation="(v) => update({ enableAnimation: v })"
            @update:animation-type="(v) => update({ animationType: v })"
            @update:enable-progress="(v) => update({ enableProgress: v })"
            @update:enable-loading="(v) => update({ enableLoading: v })"
          />

          <footer class="sp-footer">
            <el-button text :icon="RefreshLeft" @click="onReset">{{ t('stylePanel.reset') }}</el-button>
          </footer>
        </el-tab-pane>

        <el-tab-pane :label="t('stylePanel.tabUi')" name="ui">
          <!-- 界面设置：主题模式 / 显示项开关 -->
          <section class="sp-section">
            <header class="sp-section-header"><span class="sp-section-title">{{ t('stylePanel.themeMode') }}</span></header>
            <el-radio-group :model-value="s.mode" class="sp-mode" @update:model-value="(v: any) => update({ mode: v })">
              <el-radio-button value="light">{{ t('stylePanel.modeLight') }}</el-radio-button>
              <el-radio-button value="dark">{{ t('stylePanel.modeDark') }}</el-radio-button>
              <el-radio-button value="auto">{{ t('stylePanel.modeAuto') }}</el-radio-button>
            </el-radio-group>
          </section>

          <section class="sp-section">
            <header class="sp-section-header"><span class="sp-section-title">{{ t('stylePanel.interface') }}</span></header>
            <div v-for="opt in uiToggles" :key="opt.key" class="sp-row">
              <span class="sp-label">{{ t(opt.label) }}</span>
              <el-switch
                :model-value="(s as any)[opt.key]"
                @update:model-value="(v) => update({ [opt.key]: v } as any)"
              />
            </div>
          </section>

          <footer class="sp-footer">
            <el-button text :icon="RefreshLeft" @click="onReset">{{ t('stylePanel.reset') }}</el-button>
          </footer>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import { Close, RefreshLeft } from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';
import { useThemeStore, type ThemeSettings } from '@/stores/theme';
import StylePanelLayout from './StylePanelLayout.vue';
import StylePanelColorPicker from './StylePanelColorPicker.vue';
import StylePanelAnimation from './StylePanelAnimation.vue';

defineProps<{ modelValue: boolean }>();
defineEmits<{ (e: 'update:modelValue', v: boolean): void }>();

const theme = useThemeStore();
const { t } = useI18n();

const activeTab = ref<'theme' | 'ui'>('theme');

const s = computed<ThemeSettings>(() => theme.settings);

/* ===== 预设色板 ===== */
// 12 个主色候选：保留 ifinbrain 蓝紫调，但允许用户切到任何色相。
// 顺序：冷→暖，灰最末。
const PRIMARY_PRESETS = [
  '#2563eb', '#3b82f6', '#6366f1', '#8b5cf6', '#a855f7', '#d946ef',
  '#ec4899', '#ef4444', '#f97316', '#f59e0b', '#10b981', '#14b8a6',
  '#06b6d4', '#0ea5e9', '#475569', '#1f2937'
];

// 12 个顶栏色：白 / 浅蓝 / 浅米 / 深色
const HEADER_PRESETS = [
  '#ffffff', '#f8fafc', '#f1f5f9', '#e0e7ff', '#dbeafe', '#dbe1f0',
  '#fef3c7', '#fce7f3', '#dcfce7', '#fee2e2', '#0f172a', '#1e293b'
];

// 12 个菜单色：与 header 同源，加个强对比的 dark slate
const MENU_PRESETS = [
  '#ffffff', '#f8fafc', '#f1f5f9', '#e0e7ff', '#dbeafe', '#dbe1f0',
  '#0f172a', '#1e293b', '#334155', '#111827', '#1e1b4b', '#312e81'
];

const uiToggles: { key: keyof ThemeSettings; label: string }[] = [
  { key: 'collapseMenu',     label: 'stylePanel.optCollapse' },
  { key: 'fixedHeader',      label: 'stylePanel.optFixedHeader' },
  { key: 'showBreadcrumb',   label: 'stylePanel.optBreadcrumb' },
  { key: 'showTabs',         label: 'stylePanel.optTabs' },
  { key: 'showLogo',         label: 'stylePanel.optLogo' },
  { key: 'showFullscreenBtn', label: 'stylePanel.optFullscreen' },
];

async function update(patch: Partial<ThemeSettings>) {
  await theme.update(patch);
}

async function onReset() {
  try {
    await ElMessageBox.confirm(
      t('stylePanel.resetConfirm'),
      t('stylePanel.reset'),
      { type: 'warning', confirmButtonText: t('common.ok'), cancelButtonText: t('common.cancel') }
    );
    await theme.reset();
  } catch { /* 取消 */ }
}
</script>

<style scoped>
.sp-root { display: flex; flex-direction: column; height: 100%; background: var(--color-bg-elev); }

.sp-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--color-border-soft);
}
.sp-title { margin: 0; font-size: 16px; font-weight: 600; color: var(--color-text); }
.sp-close { color: var(--color-text-muted); }

.sp-tabs { flex: 1; overflow: hidden; }
.sp-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 16px; border-bottom: 1px solid var(--color-border-soft); }
.sp-tabs :deep(.el-tabs__item) { font-size: 14px; }
.sp-tabs :deep(.el-tabs__content) { padding: 0 16px; height: calc(100% - 50px); overflow-y: auto; }
.sp-tabs :deep(.el-tab-pane) { padding: 0; }

.sp-section { padding: 14px 0; border-bottom: 1px solid var(--color-border-soft); }
.sp-section-header { display: flex; justify-content: center; margin-bottom: 12px; }
.sp-section-title { font-size: 14px; font-weight: 600; color: var(--color-text); }

.sp-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; }
.sp-label { font-size: 13px; color: var(--color-text-secondary); }

.sp-mode { display: flex; width: 100%; }
.sp-mode :deep(.el-radio-button) { flex: 1; }
.sp-mode :deep(.el-radio-button__inner) { width: 100%; padding: 8px 12px; }

.sp-footer { padding: 14px 0; text-align: center; }
</style>

<style>
/* drawer 容器宽度 / 样式微调（不 scoped，因为 el-drawer 内部节点 scoped 无效） */
.el-drawer.sp-modal + .el-drawer__wrapper .el-drawer__body { padding: 0 !important; }
.el-drawer__header { display: none; }
</style>