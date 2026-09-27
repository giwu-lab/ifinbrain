<template>
  <div class="editor">
    <div class="editor-topbar">
      <div class="editor-topbar-left">
        <el-button link :icon="ArrowLeft" @click="goBack">{{ t('design.back') }}</el-button>
        <span class="editor-title">{{ page?.name || '…' }}</span>
        <span class="muted small">· {{ page?.code }}</span>
        <span v-if="page" class="editor-status" :class="page.status === 1 ? 'on' : 'off'">
          {{ page.status === 1 ? t('common.enabled') : t('common.disabled') }}
        </span>
        <span v-if="dirty" class="editor-dirty">●</span>
      </div>
      <div class="editor-topbar-right">
        <el-button :icon="View" @click="onPreview">{{ t('design.preview') }}</el-button>
        <el-button :icon="DocumentCopy" @click="onCopyJson">{{ t('design.copyJson') }}</el-button>
        <el-button type="primary" :icon="Document" :loading="saving" @click="onSave">{{ t('common.save') }}</el-button>
        <el-button
          v-if="auth.hasPerm('design:page:publish') && page && page.status !== 1"
          type="success"
          :icon="Promotion"
          :loading="publishing"
          @click="onPublish"
        >{{ t('design.publish') }}</el-button>
      </div>
    </div>

    <div class="editor-body">
      <!-- 左侧：组件库 -->
      <aside class="editor-palette">
        <div class="palette-title">{{ t('design.palette') }}</div>
        <div v-for="group in paletteGroups" :key="group.category" class="palette-group">
          <div class="palette-group-label">{{ group.label }}</div>
          <div class="palette-grid">
            <button
              v-for="b in group.items"
              :key="b.type"
              class="palette-item"
              :title="b.name"
              @click="onAdd(b.type)"
            >
              <el-icon class="palette-item-icon"><component :is="resolveIcon(b.icon)" /></el-icon>
              <span class="palette-item-name">{{ b.name }}</span>
            </button>
          </div>
        </div>
      </aside>

      <!-- 中央：画布 -->
      <main class="editor-canvas" @click.self="store.select(null)">
        <div class="canvas-frame" :style="{ padding: (rootNode.props?.padding ?? 24) + 'px', gap: (rootNode.props?.gap ?? 16) + 'px' }">
          <template v-if="(rootNode.children || []).length === 0">
            <div class="canvas-empty" @click.stop>
              <el-icon class="empty-icon"><Files /></el-icon>
              <p class="muted">{{ t('design.canvasEmpty') }}</p>
            </div>
          </template>
          <div
            v-for="(child, idx) in rootNode.children || []"
            :key="child.id"
            class="canvas-block"
            :class="store.selectedId === child.id ? 'is-selected' : ''"
            @click.stop="store.select(child.id)"
          >
            <button class="block-del" :title="t('common.delete')" @click.stop="onRemove(child.id)">
              <el-icon><Close /></el-icon>
            </button>
            <DynamicRenderer
              :node="child"
              :selected-id="store.selectedId"
              :editor-mode="true"
              :clickable="true"
              @select="store.select"
            />
          </div>
        </div>
      </main>

      <!-- 右侧：属性面板 -->
      <aside class="editor-props">
        <PropertyPanel
          :node="store.selectedNode"
          :block-metas="store.blockMetas"
          @update="onPropUpdate"
        />
        <div v-if="store.selectedNode" class="props-actions">
          <el-button type="danger" link :icon="Delete" @click="onRemove(store.selectedNode.id)">
            {{ t('design.removeBlock') }}
          </el-button>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import * as ElIcons from '@element-plus/icons-vue';
import {
  ArrowLeft, DocumentCopy, Document, View, Promotion, Files, Close, Delete
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { portalPageApi, type PortalPage } from '@/api/portalPage';
import { usePortalEditorStore } from '@/stores/portalPage';
import { BLOCK_REGISTRY } from '@/registry/blocks';
import DynamicRenderer from '@/components/portal/DynamicRenderer.vue';
import PropertyPanel from '@/components/portal/PropertyPanel.vue';

const props = defineProps<{ id: string }>();
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const store = usePortalEditorStore();

const page = ref<PortalPage | null>(null);
const saving = ref(false);
const publishing = ref(false);
const dirty = computed(() => store.dirty);

const rootNode = computed(() => store.root);

const paletteGroups = computed(() => {
  const groups = new Map<string, { category: string; label: string; items: any[] }>();
  for (const b of store.blockMetas) {
    if (!groups.has(b.category)) {
      groups.set(b.category, { category: b.category, label: t(`design.cat.${b.category}`), items: [] });
    }
    groups.get(b.category)!.items.push(b);
  }
  // 排除 layout 类型（row/col）—— 用户不主动拖布局容器，留给 Phase B+
  return Array.from(groups.values())
    .filter(g => g.category !== 'layout')
    .sort((a, b) => a.category.localeCompare(b.category));
});

function resolveIcon(name?: string) {
  if (!name) return ElIcons.Grid;
  return (ElIcons as Record<string, unknown>)[name] as any || ElIcons.Grid;
}

function goBack() {
  if (dirty.value) {
    if (!confirm(t('design.unsavedHint'))) return;
  }
  router.push('/design/page');
}

function onAdd(type: string) {
  const def = BLOCK_REGISTRY[type];
  if (!def) return;
  store.addToRoot({
    id: store.nextId(type.replace(/[^a-z0-9]/g, '_')),
    type,
    props: { ...def.defaultProps },
    layout: { x: 0, y: Infinity, w: 4, h: 2 }, // grid-layout-plus 会自动摆位
  });
}

function onRemove(id: string) {
  store.removeNode(id);
}

function onPropUpdate(node: any) {
  store.replaceNode(node);
}

async function onSave() {
  if (!page.value) return;
  saving.value = true;
  try {
    const updated = await portalPageApi.update(page.value.id, {
      name: page.value.name,
      description: page.value.description,
      layoutSchema: store.serialize(),
      roleBindings: page.value.roleBindings,
      status: page.value.status,
    });
    page.value = updated;
    store.clearDirty();
    ElMessage.success(t('common.updated'));
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally {
    saving.value = false;
  }
}

async function onPublish() {
  if (!page.value) return;
  publishing.value = true;
  try {
    // 先保存再发布
    await portalPageApi.update(page.value.id, {
      layoutSchema: store.serialize(),
    });
    const updated = await portalPageApi.publish(page.value.id);
    page.value = updated;
    ElMessage.success(t('design.published'));
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
  } finally {
    publishing.value = false;
  }
}

function onPreview() {
  if (!page.value) return;
  router.push(`/design/page/${page.value.id}/preview`);
}

async function onCopyJson() {
  try {
    const json = store.serialize();
    await navigator.clipboard.writeText(json);
    ElMessage.success(t('design.copied'));
  } catch {
    ElMessage.warning('Clipboard not available');
  }
}

onMounted(async () => {
  const id = Number(props.id);
  if (!id || isNaN(id)) {
    ElMessage.error('Invalid page id');
    router.push('/design/page');
    return;
  }
  store.setLoading(true);
  try {
    const [detail, blocks] = await Promise.all([
      portalPageApi.detail(id),
      portalPageApi.blocks(),
    ]);
    page.value = detail;
    store.setBlocks(blocks);
    store.reset(detail.layoutSchema);
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || String(e));
    router.push('/design/page');
  } finally {
    store.setLoading(false);
  }
});
</script>

<style scoped>
.editor {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 64px); /* header 高度；Layout 包裹层一致 */
  margin: -16px; /* 抵消 page-container padding 让编辑器全屏 */
  background: var(--color-bg);
}

.editor-topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  height: 52px;
  flex-shrink: 0;
}
.editor-topbar-left { display: flex; align-items: center; gap: 12px; }
.editor-title { font-weight: 600; font-size: 15px; }
.editor-status {
  font-size: 11px; padding: 1px 8px; border-radius: 2px;
  font-weight: 500;
}
.editor-status.on  { background: #e6f4ea; color: #0e8f5c; }
.editor-status.off { background: #f0f0eb; color: #8a8a85; }
.editor-dirty { color: var(--color-warning); font-size: 18px; line-height: 1; }

.editor-body {
  flex: 1;
  display: grid;
  grid-template-columns: 220px 1fr 320px;
  min-height: 0;
}

/* 左侧组件库 */
.editor-palette {
  border-right: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  overflow-y: auto;
  padding: 12px;
}
.palette-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 12px;
  padding: 0 4px;
}
.palette-group { margin-bottom: 16px; }
.palette-group-label {
  font-size: 11px;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 6px;
  padding: 0 4px;
}
.palette-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
.palette-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 6px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xs);
  background: var(--color-bg);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
  font-size: 11px;
  color: var(--color-text);
}
.palette-item:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
  color: var(--color-primary);
}
.palette-item-icon { font-size: 20px; }
.palette-item-name { font-size: 11px; }

/* 中央画布 */
.editor-canvas {
  overflow: auto;
  background: var(--color-border-soft);
  padding: 16px;
}
.canvas-frame {
  min-height: 100%;
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  display: flex;
  flex-direction: column;
}
.canvas-block {
  position: relative;
  border: 1px dashed transparent;
  border-radius: var(--radius-xs);
  padding: 8px;
  background: var(--color-bg);
  transition: border-color 0.1s;
}
.canvas-block:hover {
  border-color: var(--color-border);
}
.canvas-block.is-selected {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}
.block-del {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 22px;
  height: 22px;
  display: flex; align-items: center; justify-content: center;
  border: 1px solid var(--color-border);
  background: var(--color-bg);
  border-radius: 50%;
  cursor: pointer;
  font-size: 12px;
  color: var(--color-danger);
  opacity: 0;
  transition: opacity 0.15s;
  z-index: 2;
}
.canvas-block:hover .block-del,
.canvas-block.is-selected .block-del {
  opacity: 1;
}
.block-del:hover {
  background: var(--color-danger);
  color: #fff;
  border-color: var(--color-danger);
}

.canvas-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 80px 24px;
  text-align: center;
}
.canvas-empty .empty-icon { font-size: 40px; color: var(--color-text-muted); margin-bottom: 8px; }

/* 右侧属性面板 */
.editor-props {
  border-left: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.props-actions {
  padding: 8px 16px 12px;
  border-top: 1px solid var(--color-border-soft);
}
</style>