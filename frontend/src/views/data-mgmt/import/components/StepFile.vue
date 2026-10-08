<template>
  <div class="imp-step">
    <el-upload
      drag
      :auto-upload="false"
      :show-file-list="false"
      :accept="ACCEPT"
      :limit="1"
      :on-change="onPick"
      :on-remove="onRemove"
      class="imp-uploader"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">{{ t('dataMgmtImport.dropHere') }}</div>
      <template #tip>
        <div class="el-upload__tip">{{ t('dataMgmtImport.fileHint') }}</div>
      </template>
    </el-upload>

    <div v-if="w.file.value" class="file-box">
      <el-icon class="file-icon"><Document /></el-icon>
      <div class="file-main">
        <div class="file-name">{{ w.file.value.name }}</div>
        <div class="file-meta muted tabular">{{ formatSize(w.file.value.size) }}</div>
      </div>
      <el-button link :icon="Delete" @click="onRemove()" />
    </div>

    <el-alert
      v-if="w.previewError.value"
      type="error"
      :closable="false"
      class="parse-err"
      :title="w.previewError.value"
    />

    <el-form label-width="110px" label-position="right" class="opts-form">
      <el-form-item :label="t('dataMgmtImport.hasHeader')">
        <el-switch v-model="w.hasHeader.value" />
        <span class="hint muted">{{ t('dataMgmtImport.hasHeaderHint') }}</span>
      </el-form-item>

      <!--
        工作表只能在解析之后才知道有哪些，所以这个选择器出现在「已解析」之后：
        改选会重新走一次解析（表头 / 映射 / 预检全部刷新）。
        单 sheet 的文件不显示，避免给一个没得选的下拉。
      -->
      <el-form-item v-if="multiSheet" :label="t('dataMgmtImport.sheet')">
        <el-select
          v-model="w.sheet.value"
          style="width: 240px"
          :loading="w.uploading.value"
          @change="onSheetChange"
        >
          <el-option value="" :label="t('dataMgmtImport.firstSheet')" />
          <el-option v-for="s in sheetNames" :key="s" :value="s" :label="s" />
        </el-select>
        <span class="hint muted">{{ t('dataMgmtImport.sheetHint') }}</span>
      </el-form-item>

      <el-form-item v-if="!isExcel" :label="t('dataMgmtImport.encoding')">
        <el-select v-model="w.encoding.value" style="width: 240px">
          <el-option value="auto" :label="t('dataMgmtImport.encodingAuto')" />
          <el-option value="UTF-8" label="UTF-8" />
          <el-option value="GB18030" label="GB18030 / GBK" />
        </el-select>
        <span class="hint muted">{{ t('dataMgmtImport.encodingHint') }}</span>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { Delete } from '@element-plus/icons-vue';
import type { UploadFile } from 'element-plus';
import { formatSize, useImportWizard } from '../composables/useImportWizard';

const ACCEPT = '.xlsx,.xls,.xlsm,.csv';

const { t } = useI18n();
const w = useImportWizard();

const isExcel = computed(() => /\.(xlsx|xls|xlsm)$/i.test(w.file.value?.name ?? ''));

/** 工作表列表来自上一次解析的结果 */
const sheetNames = computed<string[]>(() => w.preview.value?.sheetNames ?? []);
const multiSheet = computed(() => sheetNames.value.length > 1);

function onPick(f: UploadFile) {
  // limit=1：始终只保留最新一次选择
  w.file.value = f.raw ?? null;
  // 换文件 → 上一次的解析结果不再可信
  w.sheet.value = '';
  w.previewError.value = '';
}

function onRemove() {
  w.file.value = null;
  w.sheet.value = '';
}

/** 换工作表要重新解析：表头和映射都会跟着变 */
async function onSheetChange() {
  await w.doPreview();
}
</script>

<style scoped>
.imp-step { padding: 4px 0 0; }

.imp-uploader :deep(.el-upload-dragger) { padding: 28px 20px; }

.file-box {
  display: flex; align-items: center; gap: 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  margin-top: 12px;
  background: var(--color-bg-elev);
}
.file-icon { color: var(--color-primary); font-size: 20px; }
.file-main { flex: 1; min-width: 0; }
.file-name {
  font-size: 13px; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.file-meta { font-size: 12px; }

.parse-err { margin-top: 12px; }

.opts-form { margin-top: 16px; }

.hint { margin-left: 12px; font-size: 12px; }
</style>