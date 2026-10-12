<template>
  <div class="page-container fade-in-up">
    <header class="page-header">
      <div class="page-header-text">
        <h1 class="page-title">{{ t('system.parserTitle') }}</h1>
        <p class="page-desc">{{ t('system.parserTitleDesc') }}</p>
      </div>
      <div class="page-header-actions">
        <el-button @click="reload" :loading="loading">{{ t('common.reload') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </div>
    </header>

    <el-alert
      v-if="!form.enabled"
      type="warning"
      :closable="false"
      show-icon
      class="banner"
    >
      <template #title>{{ t('system.parserBannerTitle') }}</template>
      <template #default>{{ t('system.parserBannerDesc') }}</template>
    </el-alert>

    <div class="parser-grid">
      <!-- 主配置 -->
      <section class="parser-config">
        <el-form :model="form" label-width="160px" class="parser-form">
          <!-- 表单内的 H2：票据解析服务API + 启用开关 -->
          <div class="form-section-head">
            <div class="head-left">
              <h2 class="section-title">{{ t('system.parserEnabledLabel') }}</h2>
              <p class="section-hint">{{ t('system.parserEnabledHint') }}</p>
            </div>
            <div class="head-controls">
              <el-switch
                v-model="form.enabled"
                :active-text="t('system.parserEnabledOn')"
                :inactive-text="t('system.parserEnabledOff')"
                inline-prompt
              />
            </div>
          </div>

          <el-form-item :label="t('system.parserBaseUrlLabel')">
            <el-input
              v-model="form.baseUrl"
              :disabled="!form.enabled"
              :placeholder="t('system.parserBaseUrlPlaceholder')"
              clearable
            >
              <template #prefix><el-icon class="input-icon"><Link /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-form-item :label="t('system.parserApiKeyLabel')" class="api-key-item">
            <div class="api-key-row">
              <!-- 已存（ok 或 failed）→ 显示状态卡 + 修改按钮；点击修改才进入编辑态 -->
              <template v-if="apiKeyStatus !== 'missing' && !editingKey">
                <el-tag
                  :type="apiKeyStatus === 'failed' ? 'warning' : 'success'"
                  effect="light"
                  round
                  class="key-state"
                >
                  <el-icon style="margin-right:4px;">
                    <component :is="apiKeyStatus === 'failed' ? WarningFilled : CircleCheckFilled" />
                  </el-icon>
                  {{ apiKeyStatus === 'failed' ? t('system.parserApiKeyFailed') : t('system.parserApiKeyConfigured') }}
                </el-tag>
                <el-button link type="primary" size="small" @click="startEditKey">
                  {{ t('system.parserApiKeyChange') }}
                </el-button>
              </template>
              <!-- 未存或在编辑 → 直接显示输入框（failed 状态下默认空，因为旧值已不可用） -->
              <el-input
                v-else
                ref="apiKeyInputRef"
                v-model="form.apiKey"
                type="password"
                show-password
                :disabled="!form.enabled"
                :placeholder="apiKeyPlaceholder"
                clearable
                @focus="clearMaskOnFocus"
              />
            </div>
          </el-form-item>

          <el-form-item :label="t('system.parserTimeoutLabel')">
            <el-input-number
              v-model="form.timeoutSeconds"
              :disabled="!form.enabled"
              :min="5"
              :max="600"
              :step="5"
            />
            <span class="unit-suffix">{{ t('system.parserTimeoutUnit') }}</span>
          </el-form-item>

          <!-- 高级设置：作为 Timeout 的子项，折叠展开 -->
          <el-collapse v-model="advancedOpen" class="advanced-collapse">
            <el-collapse-item name="adv">
              <template #title>
                <span class="collapse-title">{{ t('system.parserAdvanced') }}</span>
                <span class="collapse-hint-inline">{{ t('system.parserPathHint') }}</span>
              </template>
              <el-form-item :label="t('system.parserExtractPathLabel')">
                <el-input
                  v-model="form.extractPath"
                  :disabled="!form.enabled"
                  :placeholder="t('system.parserExtractPathPlaceholder')"
                  clearable
                />
              </el-form-item>
              <el-form-item :label="t('system.parserBatchPathLabel')">
                <el-input
                  v-model="form.batchPath"
                  :disabled="!form.enabled"
                  :placeholder="t('system.parserBatchPathPlaceholder')"
                  clearable
                />
              </el-form-item>
              <el-form-item :label="t('system.parserHealthPathLabel')">
                <el-input
                  v-model="form.healthPath"
                  :disabled="!form.enabled"
                  :placeholder="t('system.parserHealthPathPlaceholder')"
                  clearable
                />
              </el-form-item>
            </el-collapse-item>
          </el-collapse>

          <!-- 测试连通：放在高级设置之后，与 label 对齐 -->
          <div class="test-row">
            <el-button
              type="warning"
              plain
              :loading="testing"
              :disabled="!form.enabled || !form.baseUrl"
              @click="testConnection"
              class="test-btn"
            >
              <el-icon><Connection /></el-icon>
              <span class="btn-label">{{ t('system.parserTestBtn') }}</span>
            </el-button>
            <span v-if="lastTestUrl" class="test-url">{{ lastTestUrl }}</span>
          </div>
        </el-form>

        <!-- 预留：未来扩展解析服务 -->
        <section class="reserved-card">
          <header class="reserved-head">
            <span class="reserved-title">{{ t('system.parserReservedTitle') }}</span>
          </header>
          <p class="reserved-desc">{{ t('system.parserReservedDesc') }}</p>
          <div class="reserved-item">
            <div class="reserved-item-text">
              <strong>{{ t('system.parserOcrPlaceholderTitle') }}</strong>
              <p>{{ t('system.parserOcrPlaceholderDesc') }}</p>
            </div>
            <el-tag size="small" type="info" effect="plain" round class="reserved-status">
              {{ t('system.parserOcrPlaceholderStatus') }}
            </el-tag>
          </div>
        </section>
      </section>

      <!-- 状态侧栏：左对齐布局 -->
      <aside class="parser-status">
        <header class="status-head">
          <span class="status-head-title">{{ t('system.parserStatusTitle') }}</span>
        </header>
        <div class="status-body">
          <div class="status-row">
            <span class="status-label">状态</span>
            <span class="status-value" :class="form.enabled ? 'on' : 'off'">
              {{ form.enabled ? t('system.parserStatusOn') : t('system.parserStatusOff') }}
            </span>
          </div>
          <div class="status-row">
            <span class="status-label">Base URL</span>
            <span class="status-value url">{{ form.baseUrl || '—' }}</span>
          </div>
          <div class="status-row">
            <span class="status-label">API Key</span>
            <span class="status-value" :class="apiKeyValueClass">
              <template v-if="apiKeyStatus === 'ok'">
                <el-icon class="key-dot"><CircleCheckFilled /></el-icon>
                {{ t('system.parserApiKeyConfigured') }}
              </template>
              <template v-else-if="apiKeyStatus === 'failed'">
                <el-icon class="key-dot key-dot-warn"><WarningFilled /></el-icon>
                {{ t('system.parserApiKeyFailed') }}
              </template>
              <template v-else>
                <span class="muted">—</span>
              </template>
            </span>
          </div>
          <div v-if="apiKeyStatus === 'failed'" class="status-foot status-foot-warn">
            {{ t('system.parserApiKeyFailedHint') }}
          </div>
          <div v-if="!form.enabled" class="status-foot">
            {{ t('system.parserStatusOffDesc') }}
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { Link, Connection, CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue';
import { settingApi, type ParserConfig } from '@/api/setting';

const { t } = useI18n();
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const hadKey = ref(false);
const apiKeyStatus = ref<'missing' | 'ok' | 'failed'>('missing');
const editingKey = ref(false);
const advancedOpen = ref<string[]>([]);
const lastTestUrl = ref('');
const apiKeyInputRef = ref();

const apiKeyValueClass = computed(() => {
  if (apiKeyStatus.value === 'ok') return 'on';
  if (apiKeyStatus.value === 'failed') return 'warn';
  return 'off';
});

const form = reactive<ParserConfig>({
  enabled: false,
  baseUrl: '',
  apiKey: '',
  timeoutSeconds: 60,
  extractPath: '/extract',
  batchPath: '/extract/batch',
  healthPath: '/health',
});

const apiKeyPlaceholder = computed(() =>
  hadKey.value ? t('system.parserApiKeyKeepPlaceholder') : t('system.parserApiKeyPlaceholder'),
);

async function reload() {
  loading.value = true;
  try {
    const r = await settingApi.parser();
    Object.assign(form, r);
    apiKeyStatus.value = r.apiKeyStatus ?? (r.apiKey && r.apiKey.length > 0 ? 'ok' : 'missing');
    hadKey.value = r.apiKeyStatus === 'ok';
    form.apiKey = '';
    editingKey.value = false;
  } finally {
    loading.value = false;
  }
}

function startEditKey() {
  editingKey.value = true;
  // ok 时预填掩码（"这里有东西需要先删再改"），failed 时旧值不可用 → 直接空，让用户输入新 key
  form.apiKey = apiKeyStatus.value === 'ok' ? '********' : '';
  // 等 input 渲染出来再聚焦（failed 状态下让用户立即可输入；ok 状态下清掉掩码占位）
  nextTick(() => {
    apiKeyInputRef.value?.focus();
    if (form.apiKey === '********') {
      // 直接清掉，避免用户先看到一串 *
      form.apiKey = '';
    }
  });
}

/**
 * 聚焦时若还是掩码占位，自动清空，等待用户输入新 key。
 * 避免「点击修改」后看到一串 * 但不知道是默认占位还是已经填了值。
 */
function clearMaskOnFocus() {
  if (form.apiKey === '********') {
    form.apiKey = '';
  }
}

async function save() {
  saving.value = true;
  try {
    const payload: ParserConfig = { ...form };
    if (!editingKey.value) payload.apiKey = '';
    await settingApi.saveParser(payload);
    ElMessage.success(t('system.parserSaved'));
    await reload();
  } finally {
    saving.value = false;
  }
}

async function testConnection() {
  testing.value = true;
  lastTestUrl.value = '';
  try {
    const result = await settingApi.testParser({ baseUrl: form.baseUrl });
    lastTestUrl.value = result.url || '';
    if (result && result.ok) {
      ElMessage.success(t('system.parserTestOk'));
    } else {
      ElMessage.warning(t('system.parserTestFail'));
    }
  } catch {
    ElMessage.error(t('system.parserTestFail'));
  } finally {
    testing.value = false;
  }
}

onMounted(reload);
</script>

<style scoped>
/* ============================================================
   Token — 克制工程感、左对齐、不依赖 SaaS 默认
   ============================================================ */
.page-container {
  --ink: #1A1F2E;          /* 主文字 */
  --ink-2: #606266;        /* 副文字 */
  --ink-3: #909399;        /* 弱文字 */
  --line: #E4E7ED;         /* 细边 */
  --line-soft: #EEF1F6;    /* 更细边 */
  --bg-soft: #F7F8FA;      /* 浅底 */
  --accent: #4F46E5;       /* 启用态靛蓝 */
  --warn: #D97706;         /* 测试琥珀 */
}

/* ============================================================
   Page header — 左对齐 + 间距克制
   本页是解析设置页（表单 + 配置区，非表格列表），
   按 AGENTS §7.1 例外保留页面头部。
   ============================================================ */
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
}
.page-title { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.01em; }
.page-desc { margin: 4px 0 0; font-size: 13px; }
.page-header-text { min-width: 0; }
.page-header-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* ============================================================
   关闭时的 banner — 极简
   ============================================================ */
.banner { margin-bottom: 20px; }

/* ============================================================
   主网格 — 左配置 + 右状态
   ============================================================ */
.parser-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 20px;
  align-items: start;
}
@media (max-width: 1100px) {
  .parser-grid { grid-template-columns: minmax(0, 1fr); }
}

.parser-config,
.parser-status,
.reserved-card {
  background: var(--color-bg-elev);
  border: 1px solid var(--line);
  border-radius: 4px;
}

/* ============================================================
   主配置区
   ============================================================ */
.parser-form { padding: 24px 28px 20px; }

.form-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 24px;
}
.head-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.section-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--ink);
  letter-spacing: 0;
  line-height: 1.4;
}
.head-controls { flex-shrink: 0; }

.section-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.5;
}

/* form-item 间距 */
.parser-form :deep(.el-form-item) { margin-bottom: 18px; }
.parser-form :deep(.el-form-item__label) {
  font-size: 13px;
  color: var(--ink-2);
  font-weight: 400;
}
.parser-form :deep(.el-form-item__content) { width: auto; }

/* API Key 行 — 撑满整个 form-item__content，需要单独把 content 拉满 */
.parser-form :deep(.api-key-item .el-form-item__content) {
  flex: 1 1 auto;
  width: 100%;
  max-width: 100%;
}

/* 输入框紧凑 */
.parser-form :deep(.el-input__wrapper),
.parser-form :deep(.el-input-number) {
  box-shadow: 0 0 0 1px var(--line) inset;
  border-radius: 4px;
}
.parser-form :deep(.el-input__wrapper):hover,
.parser-form :deep(.el-input-number):hover {
  box-shadow: 0 0 0 1px #C0C4CC inset;
}
.parser-form :deep(.el-input.is-focus .el-input__wrapper),
.parser-form :deep(.el-input-number.is-focus) {
  box-shadow: 0 0 0 1px var(--accent) inset;
}
.input-icon { color: var(--ink-3); }

/* API Key 行 — 撑满整个 form-item__content */
.api-key-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; width: 100%; }
.api-key-row :deep(.el-input) { flex: 1 1 100%; width: 100%; min-width: 0; }
.api-key-row :deep(.el-input__wrapper) { width: 100%; }
.key-state { font-size: 12px; }

/* 字段提示：放在下一行而不是同行（更工程感） */
.field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.5;
}

/* 单位后缀 */
.unit-suffix {
  margin-left: 10px;
  font-size: 13px;
  color: var(--ink-3);
}

/* ============================================================
   高级折叠 — 极简，无图标
   ============================================================ */
.advanced-collapse {
  margin: 18px 0 4px;
  border-top: 1px solid var(--line-soft);
}
.advanced-collapse :deep(.el-collapse-item__header) {
  border: none !important;
  height: 40px;
  padding-left: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-2);
  background: transparent;
  align-items: center;
}
.advanced-collapse :deep(.el-collapse-item__header):hover {
  color: var(--ink);
}
.advanced-collapse :deep(.el-collapse-item__arrow) {
  font-size: 12px;
  color: var(--ink-3);
  margin-right: 8px;
}
.advanced-collapse :deep(.el-collapse-item__wrap) {
  border: none !important;
}
.advanced-collapse :deep(.el-collapse-item__content) {
  padding: 8px 0 4px;
}

/* 折叠标题 + 提示同行显示：标题左、提示右 */
.collapse-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-2);
}
.collapse-hint-inline {
  margin-left: 12px;
  font-size: 12px;
  font-weight: 400;
  color: var(--ink-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

/* ============================================================
   测试连通按钮 — 整行左对齐
   ============================================================ */
.test-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 0 4px;
  border-top: 1px solid var(--line-soft);
  margin-top: 4px;
}
.test-btn {
  font-weight: 500;
}
.btn-label { margin-left: 6px; }
.test-url {
  font-size: 12px;
  color: var(--ink-3);
  font-family: var(--font-mono);
  word-break: break-all;
}

/* ============================================================
   预留区 — dashed border 表达"未启用"
   ============================================================ */
.reserved-card { margin-top: 16px; padding: 20px 28px; }
.reserved-head { margin-bottom: 8px; }
.reserved-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-2);
}
.reserved-desc {
  margin: 0 0 14px;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.6;
}
.reserved-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border: 1px dashed var(--line);
  border-radius: 4px;
  background: var(--bg-soft);
}
.reserved-item-text strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.reserved-item-text p {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--ink-3);
}
.reserved-status { flex-shrink: 0; }

/* ============================================================
   状态侧栏 — 左对齐的 key/value 形式（不是大图标卡片）
   ============================================================ */
.parser-status { padding: 0; }
.status-head {
  padding: 14px 20px;
  border-bottom: 1px solid var(--line);
}
.status-head-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-2);
}
.status-body { padding: 16px 20px 20px; }
.status-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line-soft);
}
.status-row:last-of-type { border-bottom: none; }
.status-label {
  font-size: 11px;
  color: var(--ink-3);
  letter-spacing: 0.02em;
}
.status-value {
  font-size: 14px;
  color: var(--ink);
  font-weight: 500;
  word-break: break-all;
  display: flex;
  align-items: center;
  gap: 6px;
}
.status-value.on { color: var(--accent); }
.status-value.off { color: var(--ink-3); }
.status-value.warn { color: var(--warn); }
.status-value.url {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--ink-2);
  font-weight: 400;
}
.key-dot { color: var(--accent); font-size: 14px; }
.key-dot-warn { color: var(--warn); }
.muted { color: var(--ink-3); }
.status-foot {
  margin-top: 12px;
  padding: 10px 12px;
  background: var(--bg-soft);
  border-radius: 4px;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.5;
}
.status-foot-warn {
  background: #FEF3C7;
  color: #92400E;
  border-left: 3px solid var(--warn);
}
</style>