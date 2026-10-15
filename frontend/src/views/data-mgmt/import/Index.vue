<template>
  <div class="page-container fade-in-up imp-page">
    <el-tabs v-model="tab" class="imp-tabs" @tab-change="onTabChange">
      <!-- ============ 数据导入 ============ -->
      <el-tab-pane :label="t('dataMgmtImport.tabImport')" name="import">
        <!--
          数字序在这里合法：这是一条真正有序、不能乱跳的流程
          （没有文件就没法映射，没有映射就没法预检）。
          其余页面按 AGENTS §7 不该出现 01/02/03。
        -->
        <el-steps :active="w.step.value" align-center finish-status="success" class="imp-steps">
          <el-step :title="t('dataMgmtImport.step1')" />
          <el-step :title="t('dataMgmtImport.step2')" />
          <el-step :title="t('dataMgmtImport.step3')" />
          <el-step :title="t('dataMgmtImport.step4')" />
        </el-steps>

        <div class="step-body">
          <StepSource v-show="w.step.value === 0" />
          <StepFile v-show="w.step.value === 1" />
          <StepMapping v-show="w.step.value === 2" />
          <StepReview v-show="w.step.value === 3" />
        </div>

        <div class="wizard-bar">
          <el-button v-if="w.step.value > 0" @click="w.step.value -= 1">
            {{ t('common.prev') }}
          </el-button>
          <div class="bar-right">
            <el-button
              v-if="w.step.value === 0"
              type="primary"
              :disabled="!canNextFromSource"
              :loading="w.schemaLoading.value"
              @click="nextFromSource"
            >{{ t('common.next') }}</el-button>

            <el-button
              v-if="w.step.value === 1"
              type="primary"
              :disabled="!w.file.value"
              :loading="w.uploading.value"
              @click="nextFromFile"
            >{{ t('common.next') }}</el-button>

            <el-button
              v-if="w.step.value === 2"
              type="primary"
              :disabled="mappedCount === 0"
              :loading="w.validating.value"
              @click="nextFromMapping"
            >{{ t('common.next') }}</el-button>
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ 导入历史 ============ -->
      <el-tab-pane :label="t('dataMgmtImport.tabHistory')" name="history">
        <ImportHistory ref="historyRef" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import StepSource from './components/StepSource.vue';
import StepFile from './components/StepFile.vue';
import StepMapping from './components/StepMapping.vue';
import StepReview from './components/StepReview.vue';
import ImportHistory from './components/ImportHistory.vue';
import { useImportWizard } from './composables/useImportWizard';

const { t } = useI18n();
const w = useImportWizard();

const tab = ref('import');
const historyRef = ref<InstanceType<typeof ImportHistory> | null>(null);

const canNextFromSource = computed(() => !!w.datasourceId.value && !!w.table.value);

const mappedCount = computed(
  () => w.mappings.value.filter((m) => m.enabled && m.targetColumn).length,
);

function nextFromSource() {
  w.step.value = 1;
}

/** 第 2 → 3 步：先上传解析，拿到表头与目标列才能进映射（失败原因由 StepFile 就地展示） */
async function nextFromFile() {
  if (!w.file.value) return;
  const ok = await w.doPreview();
  if (ok) w.step.value = 2;
}

async function nextFromMapping() {
  if (mappedCount.value === 0) {
    ElMessage.warning(t('dataMgmtImport.noMapping'));
    return;
  }
  await w.doValidate();
  w.step.value = 3;
}

/** 切到历史 tab 时刷新：刚导完一批数据，列表要立刻能看见 */
function onTabChange(name: string | number) {
  if (name === 'history') void historyRef.value?.load();
}

onMounted(() => {
  void w.loadDatasources();
});
</script>

<style scoped>
.imp-tabs :deep(.el-tabs__header) { margin-bottom: 4px; }

.imp-steps { margin: 12px 0 20px; }

.step-body {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-elev);
  padding: 18px 20px;
  min-height: 320px;
}

.wizard-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 16px;
}
.bar-right { display: flex; gap: 10px; }
</style>
