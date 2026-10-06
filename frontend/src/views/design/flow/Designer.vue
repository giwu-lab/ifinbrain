<template>
  <div class="flow-designer">
    <!-- 顶栏 -->
    <div class="designer-topbar">
      <div class="topbar-left">
        <el-button link :icon="ArrowLeft" @click="goBack">{{ t('design.flowBack') }}</el-button>
        <span class="banner" />
        <span class="designer-title">{{ title || t('design.flowUntitled') }}</span>
        <span v-if="dirty" class="dirty-mark">●</span>
      </div>

      <div class="topbar-center">
        <el-radio-group v-model="mode" size="small" class="mode-switch">
          <el-radio-button label="design">{{ t('flowDesigner.mode.design') }}</el-radio-button>
          <el-radio-button label="preview">{{ t('flowDesigner.mode.preview') }}</el-radio-button>
          <el-radio-button label="json">{{ t('flowDesigner.mode.json') }}</el-radio-button>
        </el-radio-group>
      </div>

      <div class="topbar-right">
        <el-tooltip :content="t('flowDesigner.undo')" placement="bottom">
          <el-button link :icon="RefreshLeft" :disabled="!store.canUndo" @click="store.undo" />
        </el-tooltip>
        <el-tooltip :content="t('flowDesigner.redo')" placement="bottom">
          <el-button link :icon="RefreshRight" :disabled="!store.canRedo" @click="store.redo" />
        </el-tooltip>
        <el-tooltip :content="t('flowDesigner.autoLayout')" placement="bottom">
          <el-button link :icon="Grid" @click="onAutoLayout" />
        </el-tooltip>

        <el-divider direction="vertical" />

        <el-button type="primary" :icon="Document" :loading="saving" @click="onSave">
          {{ isNew ? t('common.create') : t('common.save') }}
        </el-button>
        <el-button
          v-if="!isNew && auth.hasPerm('design:flow:deploy')"
          :icon="Promotion"
          :loading="publishing"
          @click="onPublish"
        >{{ meta?.status === 1 ? t('design.flowUnpublish') : t('design.flowPublish') }}</el-button>
      </div>
    </div>

    <!-- 元数据 -->
    <div class="designer-meta">
      <el-form :inline="true" size="small" label-width="100px" class="meta-form">
        <el-form-item :label="t('design.formFieldLabel.code')">
          <el-input v-model="metaLocal.code" :placeholder="t('design.flowPhCode')" :disabled="!isNew" style="width: 220px;" />
        </el-form-item>
        <el-form-item :label="t('design.formFieldLabel.name')">
          <el-input v-model="metaLocal.name" :placeholder="t('design.flowPhName')" style="width: 240px;" />
        </el-form-item>
        <el-form-item :label="t('design.formFieldLabel.description')">
          <el-input v-model="metaLocal.description" :placeholder="t('design.flowPhDescription')" style="width: 320px;" />
        </el-form-item>
        <el-form-item :label="t('design.flowDefaultFormLabel')">
          <el-select v-model="metaLocal.defaultFormCode" :placeholder="t('design.flowDefaultFormPh')" filterable clearable style="width: 220px;">
            <el-option v-for="f in formOptions" :key="f.code" :label="`${f.name} (${f.code})`" :value="f.code" />
          </el-select>
        </el-form-item>
      </el-form>
    </div>

    <!-- 三栏 -->
    <div class="designer-body">
      <!-- 左侧节点 palette -->
      <aside class="flow-palette">
        <div class="palette-title">{{ t('flowDesigner.palette') }}</div>
        <div class="palette-grid">
          <button
            v-for="meta in paletteItems"
            :key="meta.type"
            class="palette-item"
            :disabled="(meta.type === 'START' && hasStart) || (meta.type === 'END' && hasEnd)"
            @click="onAdd(meta.type)"
          >
            <el-icon class="palette-item-icon" :style="{ color: meta.color, background: meta.bg }">
              <component :is="resolveIcon(meta.icon)" />
            </el-icon>
            <span class="palette-item-name">{{ t(`flowDesigner.node.${meta.type.toLowerCase()}`) }}</span>
          </button>
        </div>
        <div class="palette-hint muted">{{ t('flowDesigner.paletteHint') }}</div>
      </aside>

      <!-- 中间画布 -->
      <main class="canvas-wrap">
        <BpmnCanvas v-if="mode === 'design' || mode === 'preview'" />
        <div v-else class="json-mode">
          <div class="json-card">
            <div class="json-header">
              <span class="json-title">
                <el-icon><Document /></el-icon>
                {{ t('flowDesigner.jsonTitle') }}
              </span>
              <el-button :icon="CopyDocument" size="small" @click="copyJson">{{ t('flowDesigner.jsonCopy') }}</el-button>
            </div>
            <pre class="json-content">{{ jsonText }}</pre>
          </div>
        </div>
      </main>

      <!-- 右侧配置面板 -->
      <aside class="flow-config">
        <template v-if="store.selectedNode">
          <div class="config-title">
            <span class="banner" />
            {{ t('flowDesigner.config.nodeTitle') }}
          </div>
          <div class="config-body">
            <ApprovalNodeConfig
              v-if="store.selectedNode.data.type === 'APPROVAL'"
              :data="store.selectedNode.data"
              @patch="onPatchNode"
            />
            <ExclusiveNodeConfig
              v-else-if="store.selectedNode.data.type === 'CONDITION'"
              :data="store.selectedNode.data"
              @patch="onPatchNode"
            />
            <el-form v-else label-position="top" size="small">
              <el-form-item :label="t('flowDesigner.node.label')">
                <el-input v-model="store.selectedNode.data.label" @input="onPatchNode({ label: store.selectedNode.data.label })" />
              </el-form-item>
            </el-form>
            <div class="config-actions">
              <el-button type="danger" link :icon="Delete" @click="onRemoveNode(store.selectedNode.id)">
                {{ t('common.delete') }}
              </el-button>
            </div>
          </div>
        </template>
        <template v-else-if="store.selectedEdge">
          <div class="config-title">
            <span class="banner" />
            {{ t('flowDesigner.config.edgeTitle') }}
          </div>
          <div class="config-body">
            <EdgeConditionConfig :edge="store.selectedEdge" @patch="onPatchEdge" />
            <div class="config-actions">
              <el-button type="danger" link :icon="Delete" @click="onRemoveEdge(store.selectedEdge.id)">
                {{ t('common.delete') }}
              </el-button>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="config-empty muted">
            <el-icon><Pointer /></el-icon>
            <p>{{ t('flowDesigner.config.empty') }}</p>
          </div>
        </template>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useI18n } from 'vue-i18n';
import * as ElIcons from '@element-plus/icons-vue';
import {
  ArrowLeft, Document, Promotion, RefreshLeft, RefreshRight, Grid,
  CopyDocument, Delete, Pointer
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { useFlowDesignStore, type DesignerMode } from '@/stores/flowDesign';
import { flowDefineApi, NODE_TYPE_LIST, NODE_TYPE_META, type NodeType, type FlowNode } from '@/api/flowDefine';
import { formDesignApi, type FormDesignItem } from '@/api/formDesign';
import BpmnCanvas from '@/components/flow/BpmnCanvas.vue';
import ApprovalNodeConfig from '@/components/flow/ApprovalNodeConfig.vue';
import ExclusiveNodeConfig from '@/components/flow/ExclusiveNodeConfig.vue';
import EdgeConditionConfig from '@/components/flow/EdgeConditionConfig.vue';

const props = defineProps<{ id?: string }>();
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const store = useFlowDesignStore();

const isNew = computed(() => !props.id);
const title = computed(() => metaLocal.value.name || (metaLocal.value.code ? `[${metaLocal.value.code}]` : ''));
const dirty = computed(() => store.dirty);
const meta = computed(() => store.meta);
const mode = computed<DesignerMode>({
  get: () => store.designerMode,
  set: (v) => store.setMode(v),
});

const metaLocal = ref({ code: '', name: '', description: '', defaultFormCode: '' });
const saving = ref(false);
const publishing = ref(false);

const paletteItems = computed(() => NODE_TYPE_LIST.map((t) => ({ type: t, ...NODE_TYPE_META[t] })));
const hasStart = computed(() => store.nodes.some(n => n.data.type === 'START'));
const hasEnd = computed(() => store.nodes.some(n => n.data.type === 'END'));
const formOptions = ref<FormDesignItem[]>([]);

const jsonText = computed(() =>
  JSON.stringify({ nodes: store.nodes, edges: store.edges }, null, 2)
);

function resolveIcon(name?: string) {
  if (!name) return ElIcons.Grid;
  return (ElIcons as Record<string, unknown>)[name] as any || ElIcons.Grid;
}

function goBack() {
  if (dirty.value) {
    ElMessageBox.confirm(t('design.flowUnsavedHint'), t('common.unsaved'), { type: 'warning' })
      .then(() => router.push('/design/flow'))
      .catch(() => {});
  } else {
    router.push('/design/flow');
  }
}

function onAdd(type: NodeType) { store.addNode(type); }
function onPatchNode(patch: any) {
  if (store.selectedNodeId) store.updateNode(store.selectedNodeId, patch);
}
function onPatchEdge(patch: any) {
  if (store.selectedEdgeId) store.updateEdge(store.selectedEdgeId, patch);
}
function onRemoveNode(id: string) { store.removeNode(id); }
function onRemoveEdge(id: string) { store.removeEdge(id); }

async function onAutoLayout() {
  const result = await store.autoLayout(store.nodes, store.edges);
  store.nodes = result.nodes;
  store.commit();
  ElMessage.success(t('flowDesigner.layoutDone'));
}

async function copyJson() {
  try {
    await navigator.clipboard.writeText(jsonText.value);
    ElMessage.success(t('design.copied'));
  } catch {
    ElMessage.warning('Clipboard not available');
  }
}

async function onSave() {
  const err = store.validate();
  if (err) { ElMessage.error(err); return; }
  if (!metaLocal.value.code.trim() || !metaLocal.value.name.trim()) {
    ElMessage.warning(t('design.flowCodeNameRequired'));
    return;
  }
  saving.value = true;
  try {
    const payload = {
      code: metaLocal.value.code.trim(),
      name: metaLocal.value.name.trim(),
      description: metaLocal.value.description,
      flowJson: { nodes: store.nodes, edges: store.edges },
      defaultFormCode: metaLocal.value.defaultFormCode,
    };
    if (isNew.value) {
      const created = await flowDefineApi.create({ ...payload, status: 0 });
      store.reset(created);
      metaLocal.value = { code: created.code, name: created.name, description: created.description || '', defaultFormCode: created.defaultFormCode || '' };
      ElMessage.success(t('common.created'));
      router.replace(`/design/flow/${created.id}/edit`);
    } else {
      const updated = await flowDefineApi.update(meta.value!.id, payload);
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
      const u = await flowDefineApi.unpublish(meta.value.id);
      store.meta = u;
      ElMessage.success(t('design.flowUnpublished'));
    } else {
      const u = await flowDefineApi.publish(meta.value.id);
      store.meta = u;
      ElMessage.success(t('design.flowPublished'));
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally {
    publishing.value = false;
  }
}

// 键盘快捷键
function handleKey(e: KeyboardEvent) {
  const ae = document.activeElement;
  if (ae instanceof HTMLInputElement || ae instanceof HTMLTextAreaElement || (ae as HTMLElement)?.isContentEditable) return;
  const ctrl = e.ctrlKey || e.metaKey;
  if (ctrl && e.key.toLowerCase() === 's') { e.preventDefault(); onSave(); }
  else if (ctrl && e.key.toLowerCase() === 'z' && !e.shiftKey) { e.preventDefault(); store.undo(); }
  else if (ctrl && (e.key.toLowerCase() === 'z' && e.shiftKey || e.key.toLowerCase() === 'y')) { e.preventDefault(); store.redo(); }
  else if (ctrl && ['1','2','3'].includes(e.key)) {
    e.preventDefault();
    const m: DesignerMode = e.key === '1' ? 'design' : e.key === '2' ? 'preview' : 'json';
    store.setMode(m);
  } else if (e.key === 'Delete' || e.key === 'Backspace') {
    if (store.selectedNodeId) { e.preventDefault(); store.removeNode(store.selectedNodeId); }
    else if (store.selectedEdgeId) { e.preventDefault(); store.removeEdge(store.selectedEdgeId); }
  } else if (e.key === 'Escape') {
    store.clearSelection();
  }
}

onMounted(async () => {
  globalThis.addEventListener('keydown', handleKey);
  store.setLoading(true);
  try {
    // 加载表单列表（节点挂表单用）
    try { formOptions.value = await formDesignApi.list(); } catch { /* ignore */ }

    if (props.id) {
      const item = await flowDefineApi.detail(Number(props.id));
      store.reset(item);
      metaLocal.value = {
        code: item.code, name: item.name, description: item.description || '',
        defaultFormCode: item.defaultFormCode || '',
      };
    } else {
      store.reset();
      metaLocal.value = { code: '', name: '', description: '', defaultFormCode: '' };
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
    router.push('/design/flow');
  } finally {
    store.setLoading(false);
  }
});

onBeforeUnmount(() => globalThis.removeEventListener('keydown', handleKey));
</script>

<style scoped>
.flow-designer { display: flex; flex-direction: column; height: calc(100vh - 64px); background: var(--color-bg); }

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
.topbar-left, .topbar-right { display: flex; align-items: center; gap: 10px; }
.topbar-right { justify-content: flex-end; }
.topbar-center { display: flex; align-items: center; justify-content: center; }
.banner { display: inline-block; width: 4px; height: 18px; background: var(--color-primary); border-radius: 2px; }
.designer-title { font-weight: 600; font-size: 15px; }
.dirty-mark { color: var(--color-warning); font-size: 16px; }
.mode-switch :deep(.el-radio-button__inner) { padding: 6px 14px; }

.designer-meta {
  padding: 5px 8px;
  border-bottom: 1px solid var(--color-border-soft);
  background: var(--color-bg-elev);
}
.meta-form :deep(.el-form-item) { margin-bottom: 0; }

.designer-body {
  flex: 1;
  display: grid;
  grid-template-columns: 200px 1fr 320px;
  min-height: 0;
  overflow: hidden;
}

.flow-palette {
  border-right: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  padding: 6px;
  overflow-y: auto;
}
.palette-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 6px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.palette-title::before {
  content: '';
  display: inline-block;
  width: 4px;
  height: 14px;
  background: var(--color-primary);
  border-radius: 2px;
}
.palette-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.palette-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 6px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  cursor: pointer;
  transition: all 0.15s;
}
.palette-item:hover:not(:disabled) { border-color: var(--color-primary); }
.palette-item:disabled { opacity: 0.4; cursor: not-allowed; }
.palette-item-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  font-size: 16px;
}
.palette-item-name { font-size: 11px; color: var(--color-text); }
.palette-hint { font-size: 11px; margin-top: 6px; padding: 4px; }

.canvas-wrap { background: var(--color-border-soft); position: relative; overflow: hidden; }

.json-mode { width: 100%; height: 100%; padding: 12px; overflow: auto; }
.json-card {
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  max-width: 1200px;
  margin: 0 auto;
}
.json-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 6px 8px;
  border-bottom: 1px solid var(--color-border-soft);
  background: var(--color-bg-elev);
}
.json-title { display: inline-flex; align-items: center; gap: 6px; font-weight: 600; font-size: 13px; }
.json-content {
  margin: 0;
  padding: 8px;
  font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  background: var(--color-bg);
  max-height: 600px;
  overflow: auto;
  white-space: pre;
}

.flow-config {
  border-left: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.config-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  padding: 6px 8px;
  border-bottom: 1px solid var(--color-border-soft);
  display: flex;
  align-items: center;
  gap: 8px;
}
.config-body { flex: 1; overflow-y: auto; padding: 6px 8px; }
.config-actions {
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px solid var(--color-border-soft);
}
.config-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 30px 8px;
  font-size: 13px;
}
.config-empty .el-icon { font-size: 28px; margin-bottom: 6px; }
</style>