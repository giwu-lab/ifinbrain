<template>
  <div class="qe-page">
    <!-- top metadata bar -->
    <header class="qe-bar">
      <div class="qe-bar-left">
        <button class="qe-back" @click="goBack" :title="t('dataMgmt.qyDetailBack')">
          <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true">
            <path d="M10 3L5 8l5 5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <span>{{ t('dataMgmt.qyBackToList') }}</span>
        </button>
        <span class="qe-bar-sep" />
        <input v-model="localName" class="qe-name" :readonly="!auth.hasPerm('data:query:update')"
               @blur="onNameCommit" @keydown.enter="onNameCommit"
               :placeholder="t('dataMgmt.qyDetailNamePh')" />
        <input v-if="auth.hasPerm('data:query:update')" v-model="description"
          class="qe-desc-input" :placeholder="t('dataMgmt.qyDetailDescPh')"
          @blur="onDescCommit" />
        <span v-else-if="description" class="qe-desc-readonly">{{ description }}</span>
        <span class="qe-saved muted small" v-if="savedAt">
          · {{ formatTime(savedAt) }}
          <span class="qe-saved-rel">{{ relTime(savedAt) }}</span>
        </span>
      </div>
      <div class="qe-bar-right">
        <button v-if="auth.hasPerm('data:query:update')" class="qe-btn" @click="save" :disabled="saving">
          {{ saving ? '…' : t('dataMgmt.qyDetailSave') }}
        </button>
        <button class="qe-btn qe-btn-primary" :disabled="running || !datasourceId" @click="run">
          ▶ {{ t('dataMgmt.qyDetailRun') }}
        </button>
      </div>
    </header>

    <!-- main layout: left editor + right sidebar -->
    <div class="qe-main">
      <!-- LEFT: editor + result (draggable split) -->
      <section class="qe-editor-pane" ref="editorPaneRef">
        <!-- datasource row -->
        <div class="qe-ds-row">
          <span class="qe-ds-label">{{ t('dataMgmt.qyDetailDatasource') }}</span>
          <el-select v-model="datasourceId" size="small" filterable
                     :disabled="!auth.hasPerm('data:query:update') || !!queryId"
                     @change="onDsChange" style="width: 320px">
            <el-option v-for="ds in datasources" :key="ds.id"
              :label="`${ds.name}  ·  ${ds.dbType}`" :value="ds.id" />
          </el-select>
          <span class="qe-ds-actions">
            <button class="qe-btn qe-btn-subtle"
                    :title="t('dataMgmt.qyDetailManageHint')"
                    @click="navigateDataSources">
              <el-icon><Connection /></el-icon>{{ t('dataMgmt.qyDetailManage') }}
            </button>
            <button class="qe-btn qe-btn-subtle"
                    :disabled="!sqlText.trim()"
                    :title="t('dataMgmt.qyDetailFormatHint')"
                    @click="formatEditorSql">
              <el-icon><MagicStick /></el-icon>{{ t('dataMgmt.qyDetailFormat') }}
            </button>
          </span>
          <span class="qe-mode">{{ t('dataMgmt.qyDetailSqlMode') }}</span>
        </div>

        <!-- SQL editor (Monaco) -->
        <div class="qe-editor-stage">
          <div class="qe-editor-host" ref="editorHostRef"></div>
        </div>

        <!-- draggable divider -->
        <div class="qe-divider" @mousedown.prevent="startDrag" :title="t('dataMgmt.qyDetailResizeHint')">
          <span class="qe-divider-grip" />
        </div>

        <!-- result preview section -->
        <div class="qe-result">
          <div v-if="resultError" class="qe-result-err">
            <pre>{{ resultError }}</pre>
          </div>
          <template v-else-if="result">
            <div class="qe-result-meta">
              <span class="qe-result-ok">
                <span class="dot" />
                <span>{{ t('dataMgmt.qyDetailRunSuccess') }}</span>
                <span class="muted"> · {{ result.durationMs }} ms · {{ t('dataMgmt.qyResultRowCount', { n: result.rowCount }) }}</span>
                <span v-if="result.truncated" class="qe-truncated">
                  · {{ t('dataMgmt.qyResultTruncated', { limit: result.rowLimit }) }}
                </span>
              </span>
              <div class="qe-result-actions">
                <el-dropdown v-if="result.rows.length > 0" trigger="click" @command="exportAs">
                  <button class="qe-btn qe-btn-ghost qe-btn-sm">{{ t('dataMgmt.qyResultExport') }} ▾</button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="csv">{{ t('dataMgmt.qyExportCsv') }}</el-dropdown-item>
                      <el-dropdown-item command="xlsx">{{ t('dataMgmt.qyExportXlsx') }}</el-dropdown-item>
                      <el-dropdown-item command="json">{{ t('dataMgmt.qyExportJson') }}</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <button class="qe-btn qe-btn-ghost qe-btn-sm" @click="clearResult">×</button>
              </div>
            </div>
            <el-table v-if="result.rows.length > 0" size="small" :data="pagedRows"
              :header-cell-style="headerCellStyle" class="qe-result-table" stripe>
              <el-table-column v-for="col in result.columns" :key="col.key"
                :prop="col.key" :label="col.label" :min-width="110">
                <template #default="{ row }">
                  <span :class="['qe-cell', col.type]">{{ formatCell(row[col.key]) }}</span>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination v-if="result.rows.length > pageSize"
              v-model:current-page="resultPage" :page-size="pageSize"
              :total="result.rows.length" layout="prev, pager, next, total" small
              class="qe-result-pager" />
          </template>
          <div v-else class="qe-result-placeholder">
            <span class="muted small">点击 ▶ 运行 查看结果</span>
          </div>
        </div>
      </section>

      <!-- RIGHT: tabbed (metadata / parameters) -->
      <aside class="qe-sidebar">
        <div class="qe-sidebar-tabs">
          <button :class="['qe-tab', { active: sidebarTab === 'meta' }]"
            @click="sidebarTab = 'meta'">
            <span>{{ t('dataMgmt.qyDetailMetadata') }}</span>
          </button>
          <button :class="['qe-tab', { active: sidebarTab === 'inputs' }]"
            @click="sidebarTab = 'inputs'">
            <span>{{ t('dataMgmt.qyDetailInputs') }}</span>
            <span v-if="allParams.length" class="qe-tab-badge">{{ allParams.length }}</span>
          </button>
        </div>

        <!-- inputs tab -->
        <div v-if="sidebarTab === 'inputs'" class="qe-sidebar-section qe-inputs">
          <div class="qe-inputs-h">
            <button v-if="auth.hasPerm('data:query:update')"
              class="qe-btn qe-btn-ghost qe-btn-sm" @click="addBlankParam">
              + {{ t('dataMgmt.qyDetailAddFilter') }}
            </button>
          </div>
          <div v-if="extractedParams.length === 0 && extraParams.length === 0" class="qe-empty muted small">
            {{ t('dataMgmt.qyDetailNoParams') }}
          </div>
          <div v-else class="qe-input-list">
            <div v-for="(p, i) in allParams" :key="`${p.name}-${i}`" class="qe-input-item">
              <div class="qe-input-item-h">
                <!--
                  参数名默认是文字，旁边挂一支铅笔；点铅笔才切成输入框。
                  常驻输入框的问题：它和下面那个「参数取值」输入框长得一模一样，
                  两行都是 p1，用户分不清哪个是名字哪个是值。

                  这里**不能**用 fromSql 判定「是否手动添加」—— addBlankParam 会同时
                  把 {{pN}} 插进 SQL，手动加的参数立刻就是 fromSql=true，
                  拿它当判据等于「刚加的参数永远锁死」。

                  编辑态有三个出口：回车提交、Esc 放弃、**失焦提交**。
                  失焦也要退出，是刻意的：编辑态是个「一次性」的临时状态，
                  参数区控件挨得近（名字框紧挨类型下拉和取值框），不绑 blur 的话
                  用户点去改类型时名字还杵在编辑框里，看着像没点中、又没法继续操作。
                -->
                <span class="qe-param-id">
                  <template v-if="isRenaming(p)">
                    <el-input
                      ref="renameInputRef"
                      v-model="renameDraft"
                      size="small"
                      class="qe-param-name-input"
                      :placeholder="t('dataMgmt.qyParamNamePh')"
                      @keydown.enter="commitRename(p)"
                      @keydown.esc="cancelRename"
                      @blur="commitRename(p)"
                    />
                  </template>
                  <template v-else>
                    <span class="qe-input-name">{{ p.name }}</span>
                    <code v-if="p.cast" class="qe-param-cast" :title="t('dataMgmt.qyDetailCastHint')">::{{ p.cast }}</code>
                    <button v-if="canRenameParam()"
                            class="qe-param-edit"
                            :title="t('dataMgmt.qyParamRename')"
                            @click="startRename(p)">
                      <el-icon><EditPen /></el-icon>
                    </button>
                  </template>
                </span>
                <el-select v-if="auth.hasPerm('data:query:update')"
                  :model-value="p.type || 'string'"
                  @change="(v: string) => onParamTypeChange(p, v)"
                  size="small" class="qe-type-select">
                  <el-option value="string"   :label="t('dataMgmt.qyTypeString')" />
                  <el-option value="text"     :label="t('dataMgmt.qyTypeText')" />
                  <el-option value="number"   :label="t('dataMgmt.qyTypeNumber')" />
                  <el-option value="date"     :label="t('dataMgmt.qyTypeDate')" />
                  <el-option value="datetime" :label="t('dataMgmt.qyTypeDatetime')" />
                </el-select>
                <button v-if="!p.isHistorical"
                  class="qe-param-x"
                  :title="t('dataMgmt.qyDetailRemoveParam')"
                  @click="onRemoveParam(i)">×</button>
              </div>
              <el-input v-if="!p.type || p.type === 'string'"
                v-model="paramValues[p.name]" size="small" :placeholder="p.label || p.name" />
              <el-input v-else-if="p.type === 'text'" type="textarea" :rows="2"
                v-model="paramValues[p.name]" size="small" :placeholder="p.label || p.name" />
              <el-date-picker v-else-if="p.type === 'date'"
                v-model="paramValues[p.name]" type="date" value-format="YYYY-MM-DD"
                :placeholder="p.label || p.name" size="small" style="width:100%" />
              <el-date-picker v-else-if="p.type === 'datetime'"
                v-model="paramValues[p.name]" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                :placeholder="p.label || p.name" size="small" style="width:100%" />
              <el-input-number v-else-if="p.type === 'number'"
                v-model="paramValues[p.name]" :controls="false" size="small" style="width:100%" />
            </div>
          </div>
        </div>

        <!-- metadata tab -->
        <div v-else class="qe-sidebar-section qe-meta">
          <div v-if="schemaLoading" class="muted small">{{ t('dataMgmt.qyLoadingArgs') }}</div>
          <div v-else-if="schemaError" class="qe-result-err small">
            {{ schemaError }}
          </div>
          <div v-else>
            <input v-model="schemaSearch" class="qe-search"
              :placeholder="t('dataMgmt.qyDetailSearchSchema')" />
            <div class="qe-tables">
              <div v-for="t in filteredTables" :key="t.name" class="qe-table">
                <div class="qe-table-h" @click="toggleTable(t.name)">
                  <span class="caret" :class="{ open: openTables[t.name] }">▸</span>
                  <span class="qe-table-name">{{ t.simpleName }}</span>
                  <span class="qe-table-full muted small" v-if="t.schema">{{ t.schema }}</span>
                </div>
                <div v-if="openTables[t.name]" class="qe-cols">
                  <div v-for="c in t.columns" :key="c.name" class="qe-col"
                       @click="insertAtCursor(c.name)" :title="t.simpleName + '.' + c.name">
                    <span class="qe-col-name">{{ c.name }}</span>
                    <span class="qe-col-type muted small">{{ c.dataType }}</span>
                  </div>
                </div>
              </div>
              <div v-if="filteredTables.length === 0" class="muted small">
                {{ t('dataMgmt.qyDetailNoMatches') }}
              </div>
            </div>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Connection, EditPen, MagicStick } from '@element-plus/icons-vue';
import * as XLSX from 'xlsx';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/stores/auth';
import { queryApi, type QueryItem, type QueryParamSchema, type QueryResult } from '@/api/query';
import { datasourceApi, type DatasourceItem } from '@/api/datasource';
import { formatSql } from '@/utils/sqlFormatter';
import { http } from '@/api/http';

// Monaco worker：dev 用裸 /@fs/ 路径（Vite 6.4 不支持 ?worker bare-specifier，
// 参见 AGENTS.md §10.1 + §10.2 项目 dev-only）。
// 路径走 utils/workers.ts 集中配置，可通过 VITE_MONACO_WORKER_URL 环境变量覆盖。
import * as monaco from 'monaco-editor';
import { MONACO_WORKER_URL } from '@/utils/workers';

declare const self: { MonacoEnvironment?: any };

self.MonacoEnvironment = {
  getWorker: () => new Worker(MONACO_WORKER_URL, { type: 'module' })
};

// very minimal SQL token coloring via monarch — better than nothing
monaco.editor.defineTheme('qeTheme', {
  base: 'vs',
  inherit: true,
  rules: [
    { token: 'keyword', foreground: '2D5BFF', fontStyle: 'bold' },
    { token: 'string', foreground: '0E8F5C' },
    { token: 'number', foreground: 'B73838' },
    { token: 'comment', foreground: '8A8A85', fontStyle: 'italic' },
    { token: 'identifier.param', foreground: 'C76A00', fontStyle: 'bold' },
    { token: 'identifier', foreground: '1A1A1A' },
  ],
  colors: {
    'editor.background': '#F4F4EE',
    'editor.lineHighlightBackground': '#ECECE6',
    'editorLineNumber.foreground': '#8A8A85',
    'editorCursor.foreground': '#2D5BFF',
    'editor.selectionBackground': '#D8D8D2',
  }
});

// SQL keyword list for Monarch tokenizer
const SQL_KEYWORDS = [
  'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'IN', 'EXISTS', 'BETWEEN',
  'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'FULL', 'CROSS', 'ON',
  'GROUP', 'BY', 'ORDER', 'HAVING', 'ASC', 'DESC', 'LIMIT', 'OFFSET',
  'UNION', 'ALL', 'INTERSECT', 'EXCEPT', 'AS', 'DISTINCT', 'CASE', 'WHEN',
  'THEN', 'ELSE', 'END', 'WITH', 'OVER', 'PARTITION', 'CAST',
  'NULL', 'IS', 'LIKE', 'ILIKE', 'TRUE', 'FALSE', 'COUNT', 'SUM', 'AVG',
  'MIN', 'MAX', 'COALESCE', 'NULLIF',
];

// SQL is already a built-in Monaco language; just register our tokenizer override.
// (register() is idempotent — calling it on an existing language is safe.)
try { monaco.languages.register({ id: 'sql' }); } catch { /* built-in already */ }
monaco.languages.setMonarchTokensProvider('sql', {
  defaultToken: 'identifier',
  tokenizer: {
    root: [
      [/\/\*/, 'comment', '@comment'],
      [/--.*$/, 'comment'],
      [/\b\d+(\.\d+)?\b/, 'number'],
      [/'([^'\\]|\\.)*'/, 'string'],
      [/\$\d+/, 'number'],
      [/\{\{[a-zA-Z_][a-zA-Z0-9_]*\}\}(?:::[a-zA-Z_][a-zA-Z0-9_]*)?/, 'identifier.param'],
      [/:[a-zA-Z_][a-zA-Z0-9_]*/, 'identifier.param'],
      [new RegExp('\\b(' + SQL_KEYWORDS.join('|') + ')\\b', 'i'), 'keyword'],
      [/[a-zA-Z_][a-zA-Z0-9_.]*/, 'identifier'],
    ],
    comment: [
      [/\*\//, 'comment', '@pop'],
      [/./, 'comment'],
    ],
  },
});

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const headerCellStyle = {
  background: '#F4F4EE',
  color: '#1A1A1A',
  fontWeight: 600,
  borderBottom: '1px solid #E5E5E0',
};

// ---- state ----
const queryId = computed(() => {
  const id = route.params.id;
  if (!id || id === 'new') return null;
  return Number(id);
});

const localName = ref('');
const description = ref('');
const datasourceId = ref<number | null>(null);
const sqlText = ref('');
const savedAt = ref<string | null>(null);

const datasources = ref<DatasourceItem[]>([]);
const paramValues = reactive<Record<string, any>>({});
const extraParams = ref<QueryParamSchema[]>([]);   // 用户手动添加的（不进 SQL）

const result = ref<QueryResult | null>(null);
const resultError = ref('');
const resultPage = ref(1);
const pageSize = 50;

const running = ref(false);
const saving = ref(false);

// Monaco
let editor: any = null;
const editorHostRef = ref<HTMLElement | null>(null);
const editorPaneRef = ref<HTMLElement | null>(null);

/** editor / result 分割条：编辑器占百分比（25-80） */
const SPLIT_KEY = 'ifinbrain.qe.splitPct';
const editorPct = ref<number>(parseInt(localStorage.getItem(SPLIT_KEY) || '60', 10));
function setEditorPct(p: number) {
  editorPct.value = Math.max(25, Math.min(80, p));
  try { localStorage.setItem(SPLIT_KEY, String(Math.round(editorPct.value))); } catch { /* ignore */ }
}

/** 把 editorPct 同步到 CSS 变量（grid-template-rows 读它） */
watch([editorPct, editorPaneRef], () => {
  const el = editorPaneRef.value;
  if (el) el.style.setProperty('--editor-pct', String(Math.round(editorPct.value)));
}, { immediate: true });

function startDrag(ev: MouseEvent) {
  ev.preventDefault();
  const pane = editorPaneRef.value;
  if (!pane) return;
  const startY = ev.clientY;
  const startPct = editorPct.value;
  const paneHeight = pane.getBoundingClientRect().height;

  const onMove = (e: MouseEvent) => {
    const dy = e.clientY - startY;
    const dPct = (dy / paneHeight) * 100;
    setEditorPct(startPct + dPct);
  };
  const onUp = () => {
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
    document.body.style.cursor = '';
    document.body.style.userSelect = '';
  };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
  document.body.style.cursor = 'row-resize';
  document.body.style.userSelect = 'none';
}

/** sidebar tab: 'inputs' | 'meta' */
const sidebarTab = ref<'inputs' | 'meta'>('meta');

// schema metadata
const schema = ref<any>(null);
const schemaLoading = ref(false);
const schemaError = ref('');
const schemaSearch = ref('');
const openTables = reactive<Record<string, boolean>>({});

// ---- helpers ----

// 从 SQL 里提取 {{name}}::type 占位（占位符只有这一种语法；
// 用 Map 去重并保持首次出现的顺序，::type 只作展示、不参与执行）
const extractedParams = computed(() => {
  const re = /\{\{([a-zA-Z_][a-zA-Z0-9_]*)\}\}(?:::([a-zA-Z_][a-zA-Z0-9_]*))?/g;
  const seen = new Map<string, { name: string; cast: string | null; fromSql: boolean }>();
  let m: RegExpExecArray | null;
  while ((m = re.exec(sqlText.value)) !== null) {
    const name = m[1];
    const cast = m[2] || null;
    if (!seen.has(name)) seen.set(name, { name, cast, fromSql: true });
  }
  return [...seen.values()];
});

// 把"SQL 提取的占位" + "用户手动添加的"合并展示
const allParams = computed(() => {
  const merged: Array<{
    name: string; label?: string; type?: string; required?: boolean;
    defaultValue?: string; placeholder?: string; cast?: string | null;
    fromSql: boolean; isHistorical: boolean;
  }> = [];
  // SQL 占位用 paramSchema 里的定义（label/type/default），cast 来自 SQL
  for (const e of extractedParams.value) {
    const def = storedSchema.value.find(p => p.name === e.name);
    merged.push({
      name: e.name,
      label: def?.label || e.name,
      type: def?.type,
      required: def?.required,
      defaultValue: def?.defaultValue,
      placeholder: def?.placeholder,
      cast: e.cast,
      fromSql: true,
      isHistorical: historicalNames.value.has(e.name),
    });
  }
  // 用户手动添加的（也用 paramSchema 里的定义）
  for (const p of extraParams.value) {
    if (!extractedParams.value.some(e => e.name === p.name)) {
      merged.push({ ...p, fromSql: false, isHistorical: false });
    }
  }
  return merged;
});

// 来自 sys_query.paramsSchema 的原始定义
const storedSchema = ref<QueryParamSchema[]>([]);

/** 进入页面时快照：这些参数已落地，不可删除 */
const historicalNames = ref<Set<string>>(new Set());

const pagedRows = computed(() => {
  if (!result.value) return [];
  const start = (resultPage.value - 1) * pageSize;
  return result.value.rows.slice(start, start + pageSize).map((r, i) => {
    const obj: Record<string, any> = { __idx: start + i };
    result.value!.columns.forEach((c, j) => { obj[c.key] = r[j]; });
    return obj;
  });
});

const filteredTables = computed(() => {
  if (!schema.value) return [];
  const q = schemaSearch.value.toLowerCase().trim();
  if (!q) return schema.value.tables;
  return schema.value.tables.filter((t: any) => {
    if (t.name.toLowerCase().includes(q)) return true;
    if (t.simpleName.toLowerCase().includes(q)) return true;
    return t.columns.some((c: any) => c.name.toLowerCase().includes(q));
  });
});

// ---- lifecycle ----
onMounted(async () => {
  // load datasources（下拉要看到全部项，所以走不分页的 listAll）
  try {
    const r = await datasourceApi.listAll();
    datasources.value = r;
  } catch {}

  if (queryId.value) {
    await loadQuery(queryId.value);
  } else {
    // new
    datasourceId.value = datasources.value[0]?.id ?? null;
    localName.value = t('dataMgmt.qyDetailUntitled');
    sqlText.value = '';
    savedAt.value = null;
  }
  initEditor();
  if (datasourceId.value) fetchSchema();
});

onBeforeUnmount(() => {
  editor?.dispose();
});

// ---- data ops ----
async function loadQuery(id: number) {
  try {
    const q = await queryApi.detail(id);
    localName.value = q.name;
    description.value = q.description || '';
    datasourceId.value = q.datasourceId;
    sqlText.value = q.sqlTemplate;
    savedAt.value = q.updatedAt;
    storedSchema.value = q.paramsSchema;
    // 快照：进入页面时已落地的参数 → 不可删除
    historicalNames.value = new Set(q.paramsSchema.map(s => s.name));
    // 初始化 paramValues
    for (const p of q.paramsSchema) paramValues[p.name] = p.defaultValue || '';
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || 'Load failed');
    router.replace('/data-mgmt/query');
  }
}

/**
 * 美化当前 SQL。
 *
 * 只改编辑器里的文本，**不自动保存** —— 格式化是可逆的视觉操作，
 * 用户看一眼再决定要不要存；顺手替你落库不合适（也可能和未保存的手改冲突）。
 *
 * formatSql 内部带语义指纹兜底：一旦它自己判断「改完可能不是同一条 SQL」，
 * 会原样返回输入。这里再判一次 identical，就是告诉用户「这条我没敢动」。
 */
function formatEditorSql() {
  if (!editor) return;
  const before = sqlText.value || '';
  if (!before.trim()) return;

  const formatted = formatSql(before);
  if (formatted === before) {
    ElMessage.info(t('dataMgmt.qyFormatSkipped'));
    return;
  }
  sqlText.value = formatted;
  editor.setValue(formatted);
  // 光标停在末尾，用户能立刻看到改动范围
  editor.setPosition({ lineNumber: formatted.split('\n').length, column: 1 });
  editor.focus();
  ElMessage.success(t('dataMgmt.qyFormatDone'));
}

async function save() {
  saving.value = true;
  try {
    // 同步 extraParams 到 paramsSchema（保留所有引用关系）
    const paramsSchema: QueryParamSchema[] = [];
    for (const e of allParams.value) {
      const def = storedSchema.value.find(p => p.name === e.name) ||
                  extraParams.value.find(p => p.name === e.name) || {};
      paramsSchema.push({
        name: e.name,
        label: e.label || def.label || e.name,
        type: e.type || def.type || 'string',
        required: e.required ?? def.required ?? false,
        defaultValue: e.defaultValue ?? def.defaultValue ?? '',
        placeholder: e.placeholder ?? def.placeholder ?? '',
      });
    }

    const body = {
      name: localName.value.trim() || t('dataMgmt.qyDetailUntitled'),
      description: description.value,
      datasourceId: datasourceId.value,
      sqlTemplate: sqlText.value,
      paramsSchema,
      status: 1,
    };
    let r: any;
    if (queryId.value) {
      r = await queryApi.update(queryId.value, body);
    } else {
      r = await queryApi.create(body);
      router.replace(`/data-mgmt/query/${r.id}`);
    }
    savedAt.value = r.updatedAt;
    storedSchema.value = r.paramsSchema;
    ElMessage.success(t('dataMgmt.qyDraftSaved'));
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || 'Save failed');
  } finally {
    saving.value = false;
  }
}

function onNameCommit() {
  if (queryId.value && localName.value.trim()) {
    save();
  }
}

function onDescCommit() {
  // 描述改动也触发自动保存（已有 queryId 时）
  if (queryId.value) save();
}

function goBack() {
  // 新建但没保存的也直接回 — 用户没主动保存说明放弃
  router.push('/data-mgmt/query');
}

async function run() {
  if (!datasourceId.value) return;
  resultError.value = '';
  result.value = null;
  running.value = true;

  // 先静默保存以确保 SQL/参数已落库
  if (sqlText.value && (queryId.value || sqlText.value.trim().length > 0)) {
    try { await save(); } catch { /* save error already shown */ }
  }

  // 拉最新 id（新建场景下 router 已 replace）
  if (!queryId.value) {
    running.value = false;
    return;
  }

  // 装入参数值
  const values: Record<string, string> = {};
  for (const p of allParams.value) {
    const v = paramValues[p.name];
    if (v === null || v === undefined || v === '') continue;
    values[p.name] = String(v);
  }

  try {
    const r = await queryApi.run(queryId.value, values);
    result.value = r;
    resultPage.value = 1;
  } catch (e: any) {
    resultError.value = e?.response?.data?.message || e?.message || 'unknown';
  } finally {
    running.value = false;
  }
}

function clearResult() {
  result.value = null;
  resultError.value = '';
}

function onDsChange() {
  if (datasourceId.value) {
    extraParams.value = [];
    for (const k of Object.keys(paramValues)) delete paramValues[k];
    fetchSchema();
  }
}

function navigateDataSources() {
  router.push('/data-mgmt/datasource');
}

async function onRemoveParam(i: number) {
  const p = allParams.value[i];
  if (!p) return;

  // 来自 SQL 的占位 → 需要确认（会同时从 SQL 中删除）
  if (p.fromSql) {
    try {
      await ElMessageBox.confirm(
        t('dataMgmt.qyDetailRemoveConfirm', { name: p.name, cast: p.cast ? '::' + p.cast : '' }),
        t('dataMgmt.qyDetailRemoveTitle'),
        {
          confirmButtonText: t('common.delete'),
          cancelButtonText: t('common.cancel'),
          type: 'warning',
        }
      );
    } catch {
      return; // 用户取消
    }
    // 从 Monaco 中删除 {{name}}::type 占位
    if (editor) {
      const re = new RegExp(`\\{\\{${escapeRegex(p.name)}\\}\\}(?:::\\w+)?`, 'g');
      const matches = editor.getModel()?.findMatches(re.source, true, true, false, null, true) || [];
      // 从后往前删（避免 range 失效）
      for (const match of matches) {
        editor.executeEdits('remove-param', [{
          range: match.range, text: '', forceMoveMarkers: true,
        }]);
      }
    }
  }

  // 从 extraParams / storedSchema / paramValues 一并清理
  extraParams.value = extraParams.value.filter(ep => ep.name !== p.name);
  storedSchema.value = storedSchema.value.filter(sp => sp.name !== p.name);
  delete paramValues[p.name];
  ElMessage.success(t('dataMgmt.qyDetailRemoved'));
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function onParamTypeChange(p: any, newType: string) {
  // 优先更新 storedSchema（历史参数）；否则更新 extraParams（新增）
  const stored = storedSchema.value.find(s => s.name === p.name);
  if (stored) {
    stored.type = newType;
    storedSchema.value = [...storedSchema.value]; // 触发响应式
  } else {
    const extra = extraParams.value.find(s => s.name === p.name);
    if (extra) {
      extra.type = newType;
      extraParams.value = [...extraParams.value];
    }
  }
  // 切换类型时清掉旧值（避免类型不兼容）
  if (!paramValues[p.name] || typeof paramValues[p.name] === 'string') {
    // 保留 string 值（用户已填的文本可能仍想保留）
  } else {
    paramValues[p.name] = '';
  }
}

/**
 * 参数名是否可编辑 —— 只看写权限。
 *
 * <p>不要拿 {@code fromSql} 当判据（这是个踩过的坑）：addBlankParam 会把
 * {@code {{pN}}} 一并插进 SQL，于是「手动添加」出来的参数当场就被 extractedParams
 * 认领成 {@code fromSql=true}，用它判「是否手动添加」等于刚添加就锁死。
 */
function canRenameParam(): boolean {
  return auth.hasPerm('data:query:update');
}

/** 正在改名的参数名；null = 当前没有参数处于编辑态（同时只允许改一个） */
const editingName = ref<string | null>(null);
/**
 * 编辑态里的输入草稿，**独立于 p.name**。
 *
 * <p>之前这里直接 {@code :model-value="p.name"} 配 {@code @change}，是错的：
 * 单向绑定的 el-input 会把 {@code update:modelValue} 丢掉，prop 永远不变，
 * 于是任何一次重渲染都会把用户敲进去的字**回滚成原名**。
 * 表现是「打字打了一半就没了」或者按回车提交的其实还是旧名字。
 * 草稿独立出来后，编辑期间只有草稿在变，提交才写回 p.name。
 */
const renameDraft = ref('');
const renameInputRef = ref<any>(null);

function isRenaming(p: { name: string }): boolean {
  return editingName.value === p.name;
}

/** 点铅笔：切成输入框并全选，用户一般是想整名替换而不是接着打 */
function startRename(p: { name: string }) {
  if (editingName.value === p.name) return;
  renameDraft.value = p.name;
  editingName.value = p.name;
  nextTick(() => {
    const el = renameInputRef.value?.input
      ?? renameInputRef.value?.$el?.querySelector?.('input');
    el?.focus?.();
    el?.select?.();
  });
}

/**
 * 提交改名，三个入口共用：回车、失焦。
 *
 * <p>失焦也算提交，是刻意的 —— 编辑态是一次性的临时状态，点去改右侧类型、
 * 点去填下面的取值框都应该顺手把名字定下来，而不是留一个半开着的编辑框。
 *
 * <p>顺序：先清 editingName 再改名，无论成功、改回原名还是被重名/非法字符拒绝，
 * 编辑态都已经退出，输入框不会卡在界面上。Esc 走 cancelRename，直接丢弃草稿。
 */
function commitRename(p: { name: string }) {
  const next = renameDraft.value;
  editingName.value = null;
  onRenameParam(p, next);
}

/** Esc 放弃：直接退出编辑态，输入框随之卸载，名字回到改之前的值 */
function cancelRename() {
  editingName.value = null;
}

/**
 * 重命名参数，并把 SQL 里对它的引用一并改掉。
 *
 * <p>为什么必须连 SQL 一起改：addBlankParam 会在光标处插入 {{pN}}，参数区与 SQL
 * 是同一份契约的两面。只改 schema 不改 SQL 的话两者当场脱节 —— 参数区还叫 p2、
 * SQL 里还是 {{p1}}，运行时报「参数缺失」。
 *
 * <p>三处定义都要同步，漏一处就留下不一致：
 *   1. storedSchema —— 本次进入页面时已落地的参数（保存后会写回 DB）
 *   2. extraParams —— 本次会话新加的参数
 *   3. paramValues —— 用户已经填好的运行时取值，否则改名后输入框被清空
 *
 * <p>SQL 里只认 {@code {{old}}} 与 {@code {{old}}::type} 两种写法，前缀写死成
 * {@code {{}}，所以不存在 {{x}} 被 {{xyz}} 误伤的问题。
 */
function onRenameParam(p: { name: string }, next: string) {
  const name = (next ?? '').trim();
  // 兜底：正常路径 commitRename 已经清过 editingName，这里再清一次是防御
  // 「改名失败时编辑态没退出」——输入框一卸载，草稿就不可能留在界面上
  editingName.value = null;

  if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(name)) {
    ElMessage.warning(t('dataMgmt.qyParamBadName'));
    return;
  }
  const old = p.name;
  if (name === old) return;

  const taken = new Set(allParams.value.map((x) => x.name));
  if (taken.has(name)) {
    ElMessage.warning(t('dataMgmt.qyParamNameTaken', { name }));
    return;
  }

  // 1. 已落地的参数定义
  const stored = storedSchema.value.find((x) => x.name === old);
  if (stored) {
    stored.name = name;
    if (stored.label === old) stored.label = name;   // label 默认等于 name，一并跟过去
    storedSchema.value = [...storedSchema.value];
    // 同步「已落地」快照，否则改名后这个参数会被当成新增的、可被误删
    const snap = new Set(historicalNames.value);
    snap.delete(old);
    snap.add(name);
    historicalNames.value = snap;
  }

  // 2. 本次会话新加的参数定义
  const extra = extraParams.value.find((x) => x.name === old);
  if (extra) {
    extra.name = name;
    if (extra.label === old) extra.label = name;
    extraParams.value = [...extraParams.value];
  }

  // 3. 用户已填的运行时取值
  if (Object.prototype.hasOwnProperty.call(paramValues, old)) {
    paramValues[name] = paramValues[old];
    delete paramValues[old];
  }

  // 4. SQL 里的占位符；::type 原样带过去。
  const before = sqlText.value;
  const after = before.replace(
    new RegExp(`\\{\\{${old}\\}\\}(::[a-zA-Z_][a-zA-Z0-9_]*)?`, 'g'),
    (_m, cast) => `{{${name}}}${cast ?? ''}`,
  );
  if (after !== before) {
    sqlText.value = after;
    if (editor) {
      editor.setValue(after);
      editor.focus();
    }
  }

  ElMessage.success(t('dataMgmt.qyParamRenamed', { name }));
}

function addBlankParam() {
  let n = 1;
  while ([...extractedParams.value.map(e => e.name), ...extraParams.value.map(p => p.name)].includes('p' + n)) n++;
  const name = 'p' + n;
  extraParams.value.push({ name, label: name, type: 'string', required: false });
  // 不再生成 {{name}}::type — 类型在右侧单独设置
  insertAtCursor(`{{${name}}}`);
}

function toggleTable(name: string) {
  openTables[name] = !openTables[name];
}

function insertAtCursor(text: string) {
  if (!editor) return;
  const sel = editor.getSelection();
  editor.executeEdits('insert', [{ range: sel, text, forceMoveMarkers: true }]);
  editor.focus();
}

// ---- editor ----
function initEditor() {
  if (!editorHostRef.value) return;
  editor = monaco.editor.create(editorHostRef.value, {
    value: sqlText.value,
    language: 'sql',
    theme: 'qeTheme',
    fontFamily: '"JetBrains Mono", "Fira Code", ui-monospace, monospace',
    fontSize: 13,
    lineNumbers: 'on',
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    wordWrap: 'on',
    automaticLayout: true,
    renderWhitespace: 'selection',
    cursorBlinking: 'smooth',
    padding: { top: 16, bottom: 16 },
  });
  editor.onDidChangeModelContent(() => {
    sqlText.value = editor.getValue();
  });
  // Cmd/Ctrl+Enter → run
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => {
    run();
  });
}

// ---- schema ----
async function fetchSchema() {
  if (!datasourceId.value) return;
  schemaLoading.value = true;
  schemaError.value = '';
  try {
    const r = await http.get<any, any>(`/datamgmt/datasources/${datasourceId.value}/schema`);
    schema.value = r;
    // 元数据默认全部折叠（用户主动点击才展开）
    for (const k of Object.keys(openTables)) delete openTables[k];
  } catch (e: any) {
    schemaError.value = e?.response?.data?.message || e?.message || 'failed';
  } finally {
    schemaLoading.value = false;
  }
}

watch(datasourceId, () => {
  if (datasourceId.value) fetchSchema();
});

// ---- export ----
function exportAs(cmd: string) {
  if (!result.value) return;
  const cols = result.value.columns.map(c => c.key);
  const labels = result.value.columns.map(c => c.label);
  const filename = `${localName.value}_${formatStamp()}`;
  if (cmd === 'csv') {
    const rows = [labels.join(',')];
    for (const r of result.value.rows) rows.push(r.map(c => csvEscape(formatCell(c))).join(','));
    downloadBlob('\uFEFF' + rows.join('\n'), filename + '.csv', 'text/csv;charset=utf-8');
  } else if (cmd === 'json') {
    const arr = result.value.rows.map(r => {
      const o: Record<string, any> = {};
      cols.forEach((k, i) => { o[k] = r[i]; });
      return o;
    });
    downloadBlob(JSON.stringify(arr, null, 2), filename + '.json', 'application/json;charset=utf-8');
  } else if (cmd === 'xlsx') {
    const aoa = [labels, ...result.value.rows.map(r => r.map(formatCell))];
    const ws = XLSX.utils.aoa_to_sheet(aoa);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, 'result');
    XLSX.writeFile(wb, filename + '.xlsx');
  }
}
function csvEscape(v: string) {
  // 防 CSV formula injection（review M19）：以 =, +, -, @, \t, \r 开头的值，
  // Excel/LibreOffice 会把它当公式执行，可读取本地文件或发起 HTTP 请求。
  // 在前面加一个单引号中和（Excel 仍按字符串显示；CSV 解析时 `'` 不影响语义）。
  let safe = v;
  if (/^[=+\-@\t\r]/.test(safe)) safe = "'" + safe;
  return /[",\n]/.test(safe) ? '"' + safe.replace(/"/g, '""') + '"' : safe;
}
function downloadBlob(text: string, filename: string, type: string) {
  const blob = new Blob([text], { type });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = url; a.download = filename;
  document.body.appendChild(a); a.click(); document.body.removeChild(a); URL.revokeObjectURL(url);
}

function formatCell(v: any): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}
function formatTime(s: string) {
  if (!s) return '';
  const d = new Date(s);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function formatStamp() {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}
function relTime(s: string) {
  if (!s) return '';
  const diff = Date.now() - new Date(s).getTime();
  const min = Math.floor(diff / 60_000);
  if (min < 1) return t('dataMgmt.qyRelTimeJust');
  if (min < 60) return t('dataMgmt.qyRelTimeMin', { n: min });
  const hr = Math.floor(min / 60);
  if (hr < 24) return t('dataMgmt.qyRelTimeHour', { n: hr });
  const day = Math.floor(hr / 24);
  return t('dataMgmt.qyRelTimeDay', { n: day });
}
</script>

<style scoped>
.qe-page {
  display: flex; flex-direction: column;
  height: calc(100vh - 60px);
  background: #FAFAF7;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
}

/* top bar */
.qe-bar {
  height: 56px;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 10px;
  border-bottom: 1px solid #E5E5E0;
  background: #FFFFFF;
  flex-shrink: 0;
}
.qe-bar-left { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1; }
.qe-back {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 5px 10px 5px 6px;
  border: 1px solid #E5E5E0;
  background: #FFFFFF;
  color: #1A1A1A;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.12s, border-color 0.12s;
  flex-shrink: 0;
}
.qe-back:hover { background: #F5F5F1; border-color: #D5D5D0; }
.qe-back svg { display: block; color: #5C5C5C; }
.qe-bar-sep {
  width: 1px; height: 18px;
  background: #E5E5E0;
  flex-shrink: 0;
}
.qe-name {
  font-size: 16px; font-weight: 600;
  border: none; background: transparent; outline: none;
  color: #1A1A1A; padding: 4px 0;
  min-width: 200px; max-width: 460px;
}
.qe-name:focus { border-bottom: 1px solid #2D5BFF; }
.qe-name[readonly] { cursor: default; }
.qe-desc-input {
  width: 320px;
  flex: 0 0 auto;
  border: none; background: transparent; outline: none;
  font-size: 13px; color: #5C5C5C;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background 0.12s;
}
.qe-desc-input:hover { background: #F5F5F1; }
.qe-desc-input:focus { background: #FFFFFF; box-shadow: 0 0 0 1px #E5E5E0; }
.qe-desc-input::placeholder { color: #A0A09A; font-style: italic; }
.qe-desc-readonly {
  width: 320px;
  flex: 0 0 auto;
  font-size: 13px; color: #5C5C5C;
  padding: 4px 8px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.qe-saved { font-size: 13px; display: inline-flex; gap: 6px; align-items: baseline; }
.qe-saved-rel { color: #8A8A85; }

.qe-bar-right { display: flex; align-items: center; gap: 6px; }
.qe-btn {
  border: 1px solid #E5E5E0; background: #FFFFFF;
  padding: 6px 14px; border-radius: 6px;
  font-size: 13px; color: #1A1A1A;
  cursor: pointer; transition: background 0.12s, border-color 0.12s;
}
.qe-btn:hover:not(:disabled) { background: #F5F5F1; }
.qe-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.qe-btn-sm { padding: 4px 12px; font-size: 12px; }
.qe-btn-ghost { border-color: transparent; background: transparent; padding: 4px 10px; color: #5C5C5C; }
.qe-btn-ghost:hover:not(:disabled) { background: #F5F5F1; }

/*
  次级动作按钮：工具条里的「管理数据源 / 格式化」这类辅助操作。
  与 ghost 的区别 —— ghost 静止态是纯文字，扫一眼分不清能不能点（label 和按钮长得一样）；
  subtle 保留 1px 边框 + 极浅底 + 图标，静止态就明确是按钮，hover 再加深。
*/
.qe-btn-subtle {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 4px 10px; font-size: 12px; line-height: 1.4;
  color: #3D3D3A;
  background: #FAFAF7; border-color: #E5E5E0;
  white-space: nowrap;
  transition: background 0.12s, border-color 0.12s, color 0.12s;
}
.qe-btn-subtle .el-icon { font-size: 13px; color: #8A8A85; }
.qe-btn-subtle:hover:not(:disabled) { background: #F0EFE9; border-color: #D3D2CB; color: #1A1A1A; }
.qe-btn-subtle:hover:not(:disabled) .el-icon { color: #5C5C5C; }
.qe-btn-subtle:disabled { opacity: 0.45; cursor: not-allowed; }
.qe-btn:focus-visible { outline: 2px solid #2D5BFF; outline-offset: 1px; }
.qe-btn-primary {
  background: #2D5BFF; color: #FFFFFF; border-color: #2D5BFF; font-weight: 500;
}
.qe-btn-primary:hover:not(:disabled) { background: #1E47E0; border-color: #1E47E0; }

/* main split */
.qe-main {
  flex: 1; display: grid;
  grid-template-columns: minmax(0, 1fr) 336px;  /* sidebar +20% from 280 → 336 */
  min-height: 0;
}

/* left editor pane — grid: ds-row / editor / divider / result */
.qe-editor-pane {
  display: grid;
  grid-template-rows: 44px calc(var(--editor-pct, 60) * 1%) 6px 1fr;
  border-right: 1px solid #E5E5E0;
  background: #FFFFFF;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.qe-ds-row {
  display: flex; align-items: center; gap: 12px;
  padding: 0 14px;
  border-bottom: 1px solid #E5E5E0;
  font-size: 13px;
  min-height: 0;
}
.qe-ds-label { color: #5C5C5C; }
/* 与左侧「数据源 + select」拉开一档，组内按钮靠 6px 贴紧 —— 视觉上是一个动作组，不是一片散字 */
.qe-ds-actions { display: inline-flex; align-items: center; gap: 6px; margin-left: 4px; }
.qe-mode { margin-left: auto; color: #8A8A85; font-family: ui-monospace, monospace; font-size: 11px; }

.qe-editor-stage {
  background: #FAFAF7;
  min-height: 0;
  padding: 8px 10px;
  overflow: hidden;
}
.qe-editor-host {
  width: 100%;
  height: 100%;
  background: #F4F4EE;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0,0,0,.04);
  position: relative;
  overflow: hidden;
}

/* draggable divider */
.qe-divider {
  background: transparent;
  cursor: row-resize;
  position: relative;
  user-select: none;
}
.qe-divider::before {
  content: '';
  position: absolute; left: 28px; right: 28px;
  top: 50%; height: 1px;
  background: #E5E5E0;
  transition: background 0.12s;
}
.qe-divider:hover::before,
.qe-divider:active::before { background: #2D5BFF; }
.qe-divider-grip {
  position: absolute; left: 50%; top: 50%;
  transform: translate(-50%, -50%);
  width: 28px; height: 4px; border-radius: 2px;
  background: transparent;
  transition: background 0.12s;
}
.qe-divider:hover .qe-divider-grip { background: rgba(45, 91, 255, 0.18); }

/* result section (below divider) */
.qe-result {
  background: #FFFFFF;
  display: flex; flex-direction: column;
  padding: 4px 14px 6px;
  min-height: 0;
  overflow: hidden;
}
.qe-result-placeholder {
  display: flex; align-items: center; justify-content: center;
  height: 100%;
  background: #FFFFFF;
  border-top: 1px dashed #ECECE6;
}
.qe-result-meta {
  height: 36px;
  display: flex; align-items: center; justify-content: space-between;
  font-size: 13px; color: #1A1A1A;
  flex-shrink: 0;
}
.qe-result-ok .dot { width: 8px; height: 8px; border-radius: 50%; background: #0E8F5C; display: inline-block; margin-right: 6px; vertical-align: middle; }
.qe-result-fail .dot { width: 8px; height: 8px; border-radius: 50%; background: #B73838; display: inline-block; margin-right: 6px; vertical-align: middle; }
.qe-truncated { color: #C76A00; }
.qe-result-actions { display: flex; gap: 4px; align-items: center; }
.qe-result-err {
  padding: 6px 8px; background: #FCF1F1; border-left: 3px solid #B73838;
  color: #B73838; font-size: 13px; margin: 8px 0;
  font-family: ui-monospace, monospace;
}
.qe-result-err pre { margin: 0; white-space: pre-wrap; word-break: break-word; }
.qe-result-pager { padding: 8px 0; justify-content: flex-end; }

/* result table fills the result stage; the parent is a flex column with overflow:auto */
:deep(.qe-result-table) > .el-table__inner-wrapper { overflow: auto; }
:deep(.qe-result-table) .el-table { height: 100%; }

.qe-cell { font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: 13px; }
.qe-cell.number { color: #2D5BFF; }
.qe-cell.date, .qe-cell.datetime { color: #0E8F5C; }

/* sidebar (tabbed) */
.qe-sidebar {
  background: #FAFAF7;
  display: flex; flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.qe-sidebar-tabs {
  display: flex;
  border-bottom: 1px solid #E5E5E0;
  background: #FFFFFF;
  flex-shrink: 0;
}
.qe-tab {
  flex: 1;
  display: flex; align-items: center; justify-content: center; gap: 6px;
  padding: 5px 6px;
  border: none; background: transparent;
  font-size: 13px; font-weight: 500; color: #5C5C5C;
  cursor: pointer;
  position: relative;
  transition: color 0.12s;
}
.qe-tab:hover { color: #1A1A1A; }
.qe-tab.active { color: #2D5BFF; font-weight: 600; }
.qe-tab.active::after {
  content: '';
  position: absolute; left: 50%; bottom: -1px;
  transform: translateX(-50%);
  width: 28px; height: 2px;
  background: #2D5BFF;
}
.qe-tab-badge {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 16px; height: 16px;
  padding: 0 5px;
  background: #E5E5E0; color: #5C5C5C;
  font-size: 10px; font-weight: 600;
  border-radius: 8px;
}
.qe-tab.active .qe-tab-badge { background: #2D5BFF; color: #FFFFFF; }

.qe-sidebar-section {
  flex: 1;
  padding: 7px 8px;
  overflow: auto;
  min-height: 0;
}
.qe-inputs-h {
  display: flex; justify-content: flex-end; align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
}
.qe-empty { padding: 8px 0; }
.qe-input-list { display: flex; flex-direction: column; gap: 10px; }
.qe-input-item {
  background: #FFFFFF;
  border: 1px solid #ECECE6;
  border-radius: 6px;
  padding: 8px 10px 10px;
}
.qe-input-item-h { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
/* 参数名组：文字 + ::type 徽标 + 铅笔，编辑态时只剩输入框 */
.qe-param-id { display: inline-flex; align-items: center; gap: 4px; min-width: 0; }
.qe-input-name { font-family: ui-monospace, monospace; font-size: 13px; color: #1A1A1A; font-weight: 500; }
/* 参数名可编辑态：等宽字体与只读态一致，避免改名后字重/字宽突变 */
.qe-param-name-input { width: 132px; flex-shrink: 0; }
.qe-param-name-input :deep(.el-input__wrapper) { padding: 1px 8px; background: #FFFFFF; }
.qe-param-name-input :deep(.el-input__inner) {
  font-family: ui-monospace, monospace; font-size: 13px; font-weight: 500;
}
/* 铅笔：默认几乎不可见，hover 参数行才显形 —— 保持工具条安静，但要用鼠标就能发现 */
.qe-param-edit {
  width: 20px; height: 20px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border: none; background: transparent; border-radius: 4px;
  color: #A8A8A2; cursor: pointer;
  transition: background 0.12s, color 0.12s;
}
.qe-param-edit .el-icon { font-size: 13px; }
.qe-param-edit:hover { background: #F4F4EE; color: #2D5BFF; }
.qe-param-edit:focus-visible { outline: 2px solid #2D5BFF; outline-offset: 1px; }
.qe-type-select { width: 88px; flex-shrink: 0; margin-left: auto; }
.qe-type-select :deep(.el-input__wrapper) { padding: 1px 8px; background: #FFFFFF; }
.qe-param-cast { background: #F4F4EE; padding: 1px 5px; border-radius: 3px; color: #5C5C5C; font-size: 10px; margin-left: 4px; }
.qe-param-menu { color: #8A8A85; cursor: pointer; padding: 0 4px; }
.qe-param-x {
  width: 20px; height: 20px;
  display: inline-flex; align-items: center; justify-content: center;
  border: none; background: transparent;
  color: #8A8A85;
  font-size: 16px; line-height: 1;
  border-radius: 4px;
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.12s, color 0.12s;
}
.qe-param-x:hover { background: #FCF1F1; color: #B73838; }

.qe-search {
  width: 100%; padding: 6px 10px; font-size: 13px;
  border: 1px solid #E5E5E0; border-radius: 4px;
  background: #FFFFFF; outline: none; margin-bottom: 8px;
}
.qe-search:focus { border-color: #2D5BFF; }

.qe-tables { font-size: 13px; }
.qe-table { margin-bottom: 4px; }
.qe-table-h {
  display: flex; align-items: center; gap: 6px;
  padding: 4px 6px; cursor: pointer; border-radius: 3px;
}
.qe-table-h:hover { background: #F0F0EB; }
.qe-table-h .caret { font-size: 9px; color: #8A8A85; transition: transform 0.15s; width: 10px; display: inline-block; }
.qe-table-h .caret.open { transform: rotate(90deg); }
.qe-table-name { font-family: ui-monospace, monospace; font-weight: 500; color: #1A1A1A; }
.qe-table-full { font-family: ui-monospace, monospace; }
.qe-cols { padding: 1px 0 3px 8px; }
.qe-col {
  display: flex; align-items: center; justify-content: space-between;
  padding: 3px 8px; cursor: pointer; border-radius: 3px;
  color: #1A1A1A;
}
.qe-col:hover { background: #F0F0EB; }
.qe-col-name { font-family: ui-monospace, monospace; font-size: 12px; }
.qe-col-type { font-family: ui-monospace, monospace; }
</style>