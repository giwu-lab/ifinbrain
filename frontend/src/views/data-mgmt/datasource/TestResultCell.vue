<template>
  <div class="test-cell">
    <template v-if="inline">
      <span v-if="row.__testing" class="tr-state running">
        <el-icon class="is-loading"><Loading /></el-icon>
        {{ $t('dataMgmt.testInProgress') }}
      </span>
      <span v-else-if="latest" class="tr-state" :class="latest.ok ? 'ok' : 'fail'">
        <el-icon><component :is="latest.ok ? CircleCheckFilled : WarningFilled" /></el-icon>
        <span v-if="latest.ok">
          {{ latest.latencyMs ?? '?' }}ms · {{ latest.version || '' }}
        </span>
        <span v-else>{{ latest.message || '' }}</span>
      </span>
      <span v-else-if="row.lastTestOk === 1" class="tr-state ok">
        <el-icon><CircleCheckFilled /></el-icon>
        {{ formatTime(row.lastTestAt) }} · {{ row.lastTestMsg || '' }}
      </span>
      <span v-else-if="row.lastTestOk === 0" class="tr-state fail">
        <el-icon><WarningFilled /></el-icon>
        {{ row.lastTestMsg || $t('dataMgmt.testFail') }}
      </span>
      <span v-else class="muted">{{ $t('dataMgmt.testNeverRun') }}</span>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { CircleCheckFilled, Loading, WarningFilled } from '@element-plus/icons-vue';
import type { Datasource } from '@/api/datasource';
import { formatDateTime } from '@/utils/time';

const props = defineProps<{ row: Datasource }>();

const inline = computed(() => true);
const latest = computed(() => props.row.__lastTestResult);

/** 时间统一走 utils/time.formatDateTime：yyyy-MM-dd HH:mm:ss（AGENTS §8.3） */
function formatTime(s?: string | null) {
  return formatDateTime(s);
}
</script>

<style scoped>
.test-cell { line-height: 20px; }
.tr-state {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px;
  font-family: var(--font-mono);
}
.tr-state .el-icon { flex-shrink: 0; }
.tr-state.ok     { color: var(--color-success); }
.tr-state.fail   { color: var(--color-danger); }
.tr-state.running{ color: var(--color-warning); }
.is-loading { animation: rotating 1s linear infinite; }
@keyframes rotating { from { transform: rotate(0); } to { transform: rotate(360deg); } }
</style>