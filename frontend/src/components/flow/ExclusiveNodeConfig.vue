<template>
  <div class="exclusive-node-config">
    <el-form label-position="top" size="small">
      <el-form-item :label="t('flowDesigner.node.label')">
        <el-input v-model="local.label" :placeholder="t('flowDesigner.node.labelPh')" @input="emitPatch" />
      </el-form-item>

      <el-form-item :label="t('flowDesigner.node.branches')">
        <!--
          每一行对应一条「出线」（edge），条件直接写在这条边上 —— 这才是引擎真正读取的地方。
          早先这里是一组独立的 branches[].condition 文本框，引擎从未读取，用户填了不生效。
          分支标签仍存 node.data.branches[i].label，仅用于在节点卡片上显示分支名。
        -->
        <div v-if="!outEdges.length" class="muted small">{{ t('flowDesigner.node.noOutEdge') }}</div>
        <div v-else class="branches-editor">
          <div v-for="(e, idx) in outEdges" :key="e.id" class="branch-row">
            <el-input
              :model-value="branchLabel(idx)"
              :placeholder="t('flowDesigner.node.branchLabel')"
              size="small"
              class="branch-label"
              @update:model-value="(v: string) => setBranchLabel(idx, v)"
            />
            <span class="target mono" :title="e.target">{{ e.target }}</span>
            <el-input
              :model-value="e.data?.condition ?? ''"
              :placeholder="t('flowDesigner.edge.conditionPh')"
              size="small"
              class="branch-condition"
              @update:model-value="(v: string) => patchEdge(e.id, v)"
            />
            <el-button link :icon="Delete" size="small" class="danger-icon" @click="removeEdge(e.id)" />
          </div>
        </div>
        <div class="hint muted">{{ t('flowDesigner.node.branchEdgeHint') }}</div>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Delete } from '@element-plus/icons-vue';
import type { FlowNodeData, FlowBranch, FlowEdge, FlowEdgeData } from '@/api/flowDefine';

const props = defineProps<{ data: FlowNodeData; outEdges: FlowEdge[] }>();
const emit = defineEmits<{
  patch: [Partial<FlowNodeData>];
  'patch-edge': [string, Partial<FlowEdgeData>];
  'remove-edge': [string];
}>();
const { t } = useI18n();

const local = reactive<FlowNodeData>({ type: 'CONDITION', label: '', branches: [] });

watch(() => props.data, (d) => {
  Object.assign(local, JSON.parse(JSON.stringify(d || {})));
  if (!local.branches) local.branches = [];
}, { immediate: true, deep: true });

function branchLabel(i: number): string {
  return local.branches?.[i]?.label ?? '';
}

/** 分支标签按出线顺序对齐；出线多于标签时自动补空位，避免标签与分支错位 */
function setBranchLabel(i: number, v: string) {
  const next = [...(local.branches || [])];
  while (next.length <= i) next.push({ label: '', condition: '' } as FlowBranch);
  next[i] = { ...next[i], label: v };
  local.branches = next;
  emitPatch();
}

function patchEdge(id: string, condition: string) {
  emit('patch-edge', id, { condition });
}

function removeEdge(id: string) {
  emit('remove-edge', id);
}

function emitPatch() {
  emit('patch', { ...local });
}
</script>

<style scoped>
.branches-editor { display: flex; flex-direction: column; gap: 6px; }
.branch-row { display: flex; align-items: center; gap: 6px; }
.branch-label { width: 76px; flex-shrink: 0; }
.branch-condition { flex: 1; min-width: 0; }
.target {
  font-size: 11px;
  color: var(--color-text-muted);
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 0;
}
.mono { font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace; }
.danger-icon :deep(.el-icon) { color: var(--color-danger); }
.hint { font-size: 11px; margin-top: 6px; line-height: 1.5; }
.small { font-size: 12px; padding: 4px 0; }
</style>
