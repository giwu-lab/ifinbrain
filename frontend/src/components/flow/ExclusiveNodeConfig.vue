<template>
  <div class="exclusive-node-config">
    <el-form label-position="top" size="small">
      <el-form-item :label="t('flowDesigner.node.label')">
        <el-input v-model="local.label" :placeholder="t('flowDesigner.node.labelPh')" @input="emitPatch" />
      </el-form-item>

      <el-form-item :label="t('flowDesigner.node.branches')">
        <div class="branches-editor">
          <div v-for="(b, idx) in (local.branches || [])" :key="idx" class="branch-row">
            <el-input v-model="b.label" :placeholder="t('flowDesigner.node.branchLabel')" style="width: 90px;" size="small" @input="emitPatch" />
            <el-input v-model="b.condition" :placeholder="t('flowDesigner.node.branchCondition')" size="small" style="flex: 1;" @input="emitPatch" />
            <el-button link :icon="Delete" size="small" class="danger-icon" @click="removeBranch(idx)" />
          </div>
          <el-button :icon="Plus" size="small" link @click="addBranch">{{ t('flowDesigner.node.addBranch') }}</el-button>
        </div>
        <div class="hint muted">{{ t('flowDesigner.node.branchHint') }}</div>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Delete, Plus } from '@element-plus/icons-vue';
import type { FlowNodeData, FlowBranch } from '@/api/flowDefine';

const props = defineProps<{ data: FlowNodeData }>();
const emit = defineEmits<{ patch: [Partial<FlowNodeData>] }>();
const { t } = useI18n();

const local = reactive<FlowNodeData>({ type: 'CONDITION', label: '', branches: [] });

watch(() => props.data, (d) => {
  Object.assign(local, JSON.parse(JSON.stringify(d || {})));
  if (!local.branches) local.branches = [];
}, { immediate: true, deep: true });

function addBranch() {
  if (!local.branches) local.branches = [];
  local.branches.push({ label: '', condition: '' } as FlowBranch);
  emitPatch();
}

function removeBranch(idx: number) {
  if (!local.branches) return;
  local.branches.splice(idx, 1);
  emitPatch();
}

function emitPatch() {
  emit('patch', { ...local });
}
</script>

<style scoped>
.branches-editor { display: flex; flex-direction: column; gap: 4px; }
.branch-row { display: flex; gap: 4px; align-items: center; }
.danger-icon :deep(.el-icon) { color: var(--color-danger); }
.hint { font-size: 11px; margin-top: 4px; }
</style>