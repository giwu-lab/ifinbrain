<template>
  <div :class="['flow-node', type]" :style="{ borderColor: meta.color }">
    <div class="flow-node-icon" :style="{ background: meta.bg, color: meta.color }">
      <el-icon><component :is="resolveIcon(meta.icon)" /></el-icon>
    </div>
    <div class="flow-node-body">
      <div class="flow-node-label">{{ data.label || meta.label }}</div>
      <div v-if="data.userIds?.length || data.roleIds?.length || data.orgIds?.length" class="flow-node-assignees muted">
        <span v-if="data.userIds?.length"><el-icon><User /></el-icon> {{ data.userIds.length }}</span>
        <span v-if="data.roleIds?.length"><el-icon><Avatar /></el-icon> {{ data.roleIds.length }}</span>
        <span v-if="data.orgIds?.length"><el-icon><OfficeBuilding /></el-icon> {{ data.orgIds.length }}</span>
      </div>
      <div v-if="data.formCode" class="flow-node-form muted">
        <el-icon><Document /></el-icon> {{ data.formCode }}
      </div>
      <div v-if="data.type === 'CONDITION' && data.branches?.length" class="flow-node-branches muted">
        <span v-for="(b, i) in data.branches" :key="i" class="branch">{{ b.label }}</span>
      </div>
      <div v-if="data.type === 'APPROVAL' && data.approvalType" class="flow-node-approval muted">
        {{ approvalLabel(data.approvalType, data.completionPercent) }}
      </div>
    </div>
    <Handle type="target" :position="Position.Left" />
    <Handle type="source" :position="Position.Right" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { Handle, Position } from '@vue-flow/core';
import { User, Avatar, OfficeBuilding, Document } from '@element-plus/icons-vue';
import * as ElIcons from '@element-plus/icons-vue';
import type { FlowNodeData } from '@/api/flowDefine';
import { NODE_TYPE_META, type NodeType } from '@/api/flowDefine';

const props = defineProps<{ id: string; data: FlowNodeData; type?: string }>();
const { t } = useI18n();

const type = computed<NodeType>(() => props.data.type);

const meta = computed(() => NODE_TYPE_META[props.data.type] || NODE_TYPE_META.START);

function resolveIcon(name?: string) {
  if (!name) return ElIcons.Grid;
  return (ElIcons as Record<string, unknown>)[name] as any || ElIcons.Grid;
}

function approvalLabel(type: string, percent?: number): string {
  if (type === 'OR') return t('flowDesigner.approval.or');
  if (type === 'AND') return t('flowDesigner.approval.and');
  if (type === 'PERCENT') return t('flowDesigner.approval.percent', { n: percent ?? 100 });
  return '';
}
</script>

<style scoped>
.flow-node {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  min-width: 160px;
  max-width: 220px;
  background: var(--color-bg);
  border: 1.5px solid;
  border-radius: var(--radius-sm);
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
  transition: box-shadow 0.15s;
}
.flow-node:hover {
  box-shadow: 0 2px 6px rgba(0,0,0,0.10);
}
.flow-node.START { background: #f0f9f4; }
.flow-node.END { background: #f6f6f3; }
.flow-node-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  font-size: 16px;
  flex-shrink: 0;
}
.flow-node-body { flex: 1; min-width: 0; }
.flow-node-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.flow-node-assignees, .flow-node-form, .flow-node-branches, .flow-node-approval {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  font-size: 11px;
}
.flow-node-assignees span, .flow-node-branches .branch {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.flow-node-branches .branch {
  padding: 1px 6px;
  background: var(--color-bg);
  border-radius: 2px;
  border: 1px solid var(--color-border-soft);
}
</style>