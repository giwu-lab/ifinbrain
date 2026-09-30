<template>
  <div class="edge-condition-config">
    <el-form label-position="top" size="small">
      <el-form-item :label="t('flowDesigner.edge.source')">
        <div class="muted small mono">{{ edge.source }}</div>
      </el-form-item>
      <el-form-item :label="t('flowDesigner.edge.target')">
        <div class="muted small mono">{{ edge.target }}</div>
      </el-form-item>
      <el-form-item :label="t('flowDesigner.edge.condition')">
        <el-input v-model="conditionText" :placeholder="t('flowDesigner.edge.conditionPh')" @input="emitPatch">
          <template #prepend>
            <el-select v-model="preset" style="width: 110px;" @change="onPreset">
              <el-option label="(无)" value="" />
              <el-option :label="t('flowDesigner.edge.preset.approved')" value="approved" />
              <el-option :label="t('flowDesigner.edge.preset.rejected')" value="rejected" />
              <el-option :label="t('flowDesigner.edge.preset.spel')" value="spel" />
            </el-select>
          </template>
        </el-input>
        <div class="hint muted">{{ t('flowDesigner.edge.conditionHint') }}</div>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { FlowEdge } from '@/api/flowDefine';

const props = defineProps<{ edge: FlowEdge }>();
const emit = defineEmits<{ patch: [Partial<FlowEdge['data']>] }>();
const { t } = useI18n();

const conditionText = ref('');
const preset = ref('');

watch(() => props.edge, (e) => {
  conditionText.value = e?.data?.condition ?? '';
  const c = conditionText.value;
  if (c === 'approved') preset.value = 'approved';
  else if (c === 'rejected') preset.value = 'rejected';
  else if (c && c.startsWith('${')) preset.value = 'spel';
  else preset.value = '';
}, { immediate: true, deep: true });

function onPreset(p: string) {
  if (p === 'approved' || p === 'rejected') {
    conditionText.value = p;
    emitPatch();
  } else if (p === 'spel' && !conditionText.value.startsWith('${')) {
    conditionText.value = '${...}';
    emitPatch();
  } else if (p === '') {
    conditionText.value = '';
    emitPatch();
  }
}

function emitPatch() {
  emit('patch', { condition: conditionText.value });
}
</script>

<style scoped>
.edge-condition-config .muted { font-size: 11px; padding: 4px 0; }
.mono { font-family: 'SF Mono', 'JetBrains Mono', Menlo, monospace; }
.hint { font-size: 11px; margin-top: 4px; }
</style>