<!--
  FormDesigner —— 设计器总壳（仿 mfish form-designer）。
  顶栏：返回 / 标题 / 模式切换（build/preview/json）/ 撤销重做 / 设备 / 缩放 / 保存 / 发布
  左侧：Tab（组件库 / 大纲）
  中间：FormCanvas（按 mode）
  右侧：FormFieldProps
  meta 行：code / name / description
  ★ 键盘快捷键：Ctrl+S / Ctrl+Z / Ctrl+Shift+Z / Delete / Esc / Ctrl+1/2/3
-->
<template>
  <div class="form-designer">
    <!-- ============== 顶栏 ============== -->
    <div class="designer-topbar">
      <div class="topbar-left">
        <el-button link :icon="ArrowLeft" @click="goBack">{{ t('design.formBack') }}</el-button>
        <span class="banner" />
        <span class="designer-title">{{ title || t('design.formUntitled') }}</span>
        <span v-if="dirty" class="dirty-mark" :title="t('design.formUnsavedHint')">●</span>
      </div>

      <div class="topbar-center">
        <el-radio-group v-model="mode" size="small" class="mode-switch">
          <el-radio-button label="build">{{ t('formDesigner.mode.build') }}</el-radio-button>
          <el-radio-button label="preview">{{ t('formDesigner.mode.preview') }}</el-radio-button>
          <el-radio-button label="json">{{ t('formDesigner.mode.json') }}</el-radio-button>
        </el-radio-group>
      </div>

      <div class="topbar-right">
        <el-tooltip :content="t('formDesigner.undo')" placement="bottom">
          <el-button link :icon="RefreshLeft" :disabled="!store.canUndo" @click="store.undo" />
        </el-tooltip>
        <el-tooltip :content="t('formDesigner.redo')" placement="bottom">
          <el-button link :icon="RefreshRight" :disabled="!store.canRedo" @click="store.redo" />
        </el-tooltip>

        <el-divider direction="vertical" />

        <!-- 设备模式切换（仅 build / preview 模式可见） -->
        <template v-if="mode !== 'json'">
          <el-radio-group v-model="store.deviceMode" size="small" class="device-switch">
            <el-tooltip :content="t('formDesigner.device.desktop')" placement="bottom">
              <el-radio-button label="desktop"><el-icon><Monitor /></el-icon></el-radio-button>
            </el-tooltip>
            <el-tooltip :content="t('formDesigner.device.tablet')" placement="bottom">
              <el-radio-button label="tablet"><el-icon><Iphone /></el-icon></el-radio-button>
            </el-tooltip>
            <el-tooltip :content="t('formDesigner.device.mobile')" placement="bottom">
              <el-radio-button label="mobile"><el-icon><Cellphone /></el-icon></el-radio-button>
            </el-tooltip>
          </el-radio-group>

          <el-tooltip :content="t('formDesigner.zoom')" placement="bottom">
            <el-dropdown @command="onZoom" trigger="click">
              <el-button link>{{ Math.round(store.zoom * 100) }}%</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="0.5">50%</el-dropdown-item>
                  <el-dropdown-item command="0.75">75%</el-dropdown-item>
                  <el-dropdown-item command="1">100%</el-dropdown-item>
                  <el-dropdown-item command="1.25">125%</el-dropdown-item>
                  <el-dropdown-item command="1.5">150%</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </el-tooltip>
        </template>

        <el-divider direction="vertical" />

        <el-button :icon="View" @click="onPreviewInternal">{{ t('design.formPreviewLive') }}</el-button>
        <el-button type="primary" :icon="Document" :loading="saving" @click="onSave">
          {{ isNew ? t('common.create') : t('common.save') }}
        </el-button>
        <el-button
          v-if="!isNew && auth.hasPerm('design:form:publish')"
          :icon="Promotion"
          :loading="publishing"
          @click="onPublish"
        >{{ meta?.status === 1 ? t('design.formUnpublish') : t('design.formPublish') }}</el-button>
      </div>
    </div>

    <!-- ============== 表单元数据 ============== -->
    <div class="designer-meta">
      <el-form :inline="true" size="small" label-width="100px" class="meta-form">
        <el-form-item :label="t('design.formFieldLabel.code')">
          <el-input v-model="metaLocal.code" :placeholder="t('design.formPhCode')" :disabled="!isNew" style="width: 220px;" />
        </el-form-item>
        <el-form-item :label="t('design.formFieldLabel.name')">
          <el-input v-model="metaLocal.name" :placeholder="t('design.formPhName')" style="width: 240px;" />
        </el-form-item>
        <el-form-item :label="t('design.formFieldLabel.description')">
          <el-input v-model="metaLocal.description" :placeholder="t('design.formPhDescription')" style="width: 360px;" />
        </el-form-item>
      </el-form>
    </div>

    <!-- ============== 三栏 ============== -->
    <div class="designer-body">
      <!-- 左侧：Tab（组件库 / 大纲） -->
      <div class="left-pane">
        <div class="left-tabs">
          <button
            class="left-tab"
            :class="{ active: leftTab === 'palette' }"
            @click="leftTab = 'palette'"
          >
            <el-icon><Grid /></el-icon>
            <span>{{ t('formDesigner.palette') }}</span>
          </button>
          <button
            class="left-tab"
            :class="{ active: leftTab === 'outline' }"
            @click="leftTab = 'outline'"
          >
            <el-icon><List /></el-icon>
            <span>{{ t('formDesigner.outline') }}</span>
          </button>
        </div>
        <div class="left-content">
          <FormFieldPalette v-show="leftTab === 'palette'" @add="onAdd" />
          <FormOutline v-show="leftTab === 'outline'" />
        </div>
      </div>

      <!-- 中间：画布 -->
      <main class="canvas-wrap">
        <FormCanvas
          :fields="store.fields"
          :selected-id="store.selectedId"
          :mode="mode"
          :canvas-width="store.canvasWidth"
          :zoom="store.zoom"
          :grid="store.grid"
          @select="store.select"
          @remove="store.removeField"
          @move="onMove"
          @duplicate="onDuplicate"
          @layout="onLayout"
        />
      </main>

      <!-- 右侧：属性 -->
      <div class="right-pane">
        <FormFieldProps
          :field="store.selectedField"
          :fields="store.fields"
          :grid="store.grid"
          @patch="onPatch"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useI18n } from 'vue-i18n';
import {
  ArrowLeft, Document, View, Promotion, RefreshLeft, RefreshRight,
  Monitor, Iphone, Cellphone, Grid, List
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { useFormDesignStore, type DesignerMode } from '@/stores/formDesign';
import { formDesignApi, type FieldType } from '@/api/formDesign';
import FormFieldPalette from '@/components/form/FormFieldPalette.vue';
import FormCanvas from '@/components/form/FormCanvas.vue';
import FormFieldProps from '@/components/form/FormFieldProps.vue';
import FormOutline from '@/components/form/FormOutline.vue';

const props = defineProps<{ id?: string }>();
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const store = useFormDesignStore();

const isNew = computed(() => !props.id);
const title = computed(() => metaLocal.value.name || (metaLocal.value.code ? `[${metaLocal.value.code}]` : ''));
const dirty = computed(() => store.dirty);
const meta = computed(() => store.meta);
const mode = computed<DesignerMode>({
  get: () => store.designerMode,
  set: (v) => store.setMode(v),
});

const leftTab = ref<'palette' | 'outline'>('palette');

const metaLocal = ref({ code: '', name: '', description: '' });
const saving = ref(false);
const publishing = ref(false);

function goBack() {
  if (dirty.value) {
    ElMessageBox.confirm(t('design.formUnsavedHint'), t('common.unsaved'), { type: 'warning' })
      .then(() => router.push('/design/form'))
      .catch(() => { /* cancelled */ });
  } else {
    router.push('/design/form');
  }
}

function onAdd(type: FieldType) {
  store.addField(type);
  leftTab.value = 'outline';
}

function onPatch(patch: any) {
  if (!store.selectedId) return;
  store.updateField(store.selectedId, patch);
}

function onMove(id: string, direction: 'up' | 'down') {
  store.moveField(id, direction);
}

function onDuplicate(id: string) {
  store.duplicate(id);
}

/**
 * 画布拖拽 / 改宽度。
 *
 * immediate=false 是拖拽过程中的每帧调用（只改内存），true 是松手时的最终提交。
 * 分开是为了不让撤销栈被逐像素压满 —— 见 FormCanvas.startDrag 的注释。
 */
function onLayout(id: string, patch: any, immediate = true) {
  store.setLayout(id, patch, immediate);
}

function onZoom(cmd: string) {
  store.setZoom(Number(cmd));
}

async function onSave() {
  const err = store.validate();
  if (err) { ElMessage.error(err); return; }
  if (!metaLocal.value.code.trim() || !metaLocal.value.name.trim()) {
    ElMessage.warning(t('design.formCodeNameRequired'));
    return;
  }
  saving.value = true;
  try {
    if (isNew.value) {
      const created = await formDesignApi.create({
        code: metaLocal.value.code.trim(),
        name: metaLocal.value.name.trim(),
        description: metaLocal.value.description,
        fields: store.fields,
        status: 0,
      });
      store.reset(created);
      metaLocal.value = { code: created.code, name: created.name, description: created.description || '' };
      ElMessage.success(t('common.created'));
      router.replace(`/design/form/${created.id}/edit`);
    } else {
      const updated = await formDesignApi.update(meta.value!.id, {
        code: metaLocal.value.code.trim(),
        name: metaLocal.value.name.trim(),
        description: metaLocal.value.description,
        fields: store.fields,
      });
      store.meta = updated;
      store.clearDirty();
      ElMessage.success(t('common.updated'));
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally {
    saving.value = false;
  }
}

async function onPublish() {
  if (!meta.value) return;
  publishing.value = true;
  try {
    if (meta.value.status === 1) {
      const u = await formDesignApi.unpublish(meta.value.id);
      store.meta = u;
      ElMessage.success(t('design.formUnpublished'));
    } else {
      const u = await formDesignApi.publish(meta.value.id);
      store.meta = u;
      ElMessage.success(t('design.formPublished'));
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally {
    publishing.value = false;
  }
}

function onPreviewInternal() {
  if (meta.value?.id) router.push(`/design/form/${meta.value.id}/preview`);
  else ElMessage.warning(t('design.formSaveFirst'));
}

// ============== 键盘快捷键 ==============

function handleKey(e: KeyboardEvent) {
  const ae = document.activeElement;
  if (ae instanceof HTMLInputElement || ae instanceof HTMLTextAreaElement || (ae as HTMLElement)?.isContentEditable) {
    return;
  }
  const ctrl = e.ctrlKey || e.metaKey;

  if (ctrl && e.key.toLowerCase() === 's') {
    e.preventDefault();
    onSave();
  } else if (ctrl && e.key.toLowerCase() === 'z' && !e.shiftKey) {
    e.preventDefault();
    store.undo();
  } else if (ctrl && (e.key.toLowerCase() === 'z' && e.shiftKey || e.key.toLowerCase() === 'y')) {
    e.preventDefault();
    store.redo();
  } else if (ctrl && ['1', '2', '3'].includes(e.key)) {
    e.preventDefault();
    const m: DesignerMode = e.key === '1' ? 'build' : e.key === '2' ? 'preview' : 'json';
    store.setMode(m);
  } else if (e.key === 'Delete' || e.key === 'Backspace') {
    if (store.selectedId) {
      e.preventDefault();
      store.removeField(store.selectedId);
    }
  } else if (e.key === 'Escape') {
    store.select(null);
  }
}

onMounted(() => {
  globalThis.addEventListener('keydown', handleKey);
});

onBeforeUnmount(() => {
  globalThis.removeEventListener('keydown', handleKey);
});

watch(() => store.designerMode, (m) => {
  // 模式切换时清空选中（保持画布焦点）
});

// ============== 数据加载 ==============

onMounted(async () => {
  store.setLoading(true);
  try {
    if (props.id) {
      const item = await formDesignApi.detail(Number(props.id));
      store.reset(item);
      metaLocal.value = { code: item.code, name: item.name, description: item.description || '' };
    } else {
      store.reset();
      metaLocal.value = { code: '', name: '', description: '' };
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
    router.push('/design/form');
  } finally {
    store.setLoading(false);
  }
});
</script>

<style scoped>
.form-designer {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 64px);
  background: var(--color-bg);
}

/* 顶栏 */
.designer-topbar {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 5px 8px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  height: 56px;
  flex-shrink: 0;
  gap: 12px;
}
.topbar-left { display: flex; align-items: center; gap: 10px; }
.topbar-right { display: flex; align-items: center; gap: 6px; justify-content: flex-end; }
.topbar-center { display: flex; align-items: center; justify-content: center; }

.banner {
  display: inline-block;
  width: 4px;
  height: 18px;
  background: var(--color-primary);
  border-radius: 2px;
}
.designer-title { font-weight: 600; font-size: 15px; color: var(--color-text); }
.dirty-mark { color: var(--color-warning); font-size: 16px; }

.mode-switch :deep(.el-radio-button__inner) { padding: 6px 14px; }

/* 元数据 */
.designer-meta {
  padding: 5px 8px;
  border-bottom: 1px solid var(--color-border-soft);
  background: var(--color-bg-elev);
}
.meta-form :deep(.el-form-item) { margin-bottom: 0; }

/* 三栏 */
.designer-body {
  flex: 1;
  display: grid;
  grid-template-columns: 260px 1fr 320px;
  min-height: 0;
  overflow: hidden;
}

.left-pane {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  overflow: hidden;
}
.left-tabs {
  display: flex;
  border-bottom: 1px solid var(--color-border-soft);
  background: var(--color-bg);
}
.left-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 4px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: var(--color-text-secondary);
  border-bottom: 2px solid transparent;
  transition: color 0.15s, border-color 0.15s;
}
.left-tab:hover { color: var(--color-primary); }
.left-tab.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
}
.left-content { flex: 1; overflow: hidden; display: flex; }

.canvas-wrap {
  background: var(--color-border-soft);
  overflow: hidden;
  position: relative;
}

.right-pane {
  border-left: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  overflow: hidden;
  display: flex;
}
</style>