<!--
  FormFieldProps —— 字段属性面板（mfish 风格：分组 + 类型感知）
  分组：基础 / 选项 / 校验 / 高级
-->
<template>
  <aside class="form-field-props">
    <template v-if="!field">
      <div class="props-empty muted">
        <el-icon class="empty-icon"><Document /></el-icon>
        <p>{{ t('formDesigner.propsEmpty') }}</p>
      </div>
    </template>
    <template v-else>
      <div class="props-title">
        <span class="props-title-text">{{ t('formDesigner.propsTitle') }}</span>
        <span class="props-title-type muted">{{ typeLabel(field.type) }}</span>
      </div>

      <div class="props-scroll">
        <!-- ============ 基础属性 ============ -->
        <div class="props-section">
          <div class="props-section-label">{{ t('formDesigner.section.basic') }}</div>
          <el-form label-position="top" size="small" class="props-form">
            <el-form-item :label="t('formDesigner.field.label')" required>
              <el-input v-model="local.label" :placeholder="t('formDesigner.field.labelPh')" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.key')" required>
              <el-input v-model="local.key" :placeholder="t('formDesigner.field.keyPh')" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.type')">
              <el-select v-model="local.type" disabled style="width: 100%;">
                <el-option :value="local.type" :label="typeLabel(local.type)" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.placeholder')">
              <el-input v-model="local.placeholder" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.help')">
              <el-input v-model="local.help" type="textarea" :rows="2" @input="emitPatch" />
            </el-form-item>
            <!-- 默认值是字符串语义：分割线无值，表格值是对象数组，两者都不适用 -->
            <el-form-item v-if="field.type !== 'DIVIDER' && field.type !== 'TABLE'"
                          :label="t('formDesigner.field.defaultValue')">
              <el-input v-model="local.defaultValue" @input="emitPatch" />
            </el-form-item>
            <el-form-item v-if="field.type !== 'DIVIDER'">
              <el-checkbox v-model="local.required" @change="emitPatch">
                {{ field.type === 'TABLE'
                  ? t('formDesigner.table.requireAtLeastOneRow')
                  : t('formDesigner.field.required') }}
              </el-checkbox>
            </el-form-item>          </el-form>
        </div>

        <!-- ============ 表达式 ============ -->
        <div v-if="fields?.length" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.expr.section') }}</div>
          <p class="props-hint">{{ t('formDesigner.expr.hint') }}</p>

          <el-alert
            v-for="issue in exprIssues"
            :key="issue.slotLabel + issue.detail"
            :title="issueText(issue)"
            :type="issue.kind === 'undefined-ref' ? 'warning' : 'error'"
            :closable="false"
            show-icon
            class="expr-issue"
          />

          <div v-if="refCandidates.length" class="expr-refs">
            <span class="expr-refs-label muted">{{ t('formDesigner.expr.refs') }}</span>
            <code v-for="k in refCandidates" :key="k" class="expr-ref-chip">$form.{{ k }}</code>
          </div>
          <p v-else class="props-hint">{{ t('formDesigner.expr.noRefs') }}</p>
        </div>

        <!-- ============ 条件逻辑 ============ -->
        <div v-if="fields?.length && field.type !== 'DIVIDER'" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.cond.section') }}</div>
          <p class="props-hint">{{ t('formDesigner.cond.hint') }}</p>
          <el-form label-position="top" size="small" class="props-form">
            <el-form-item :label="t('formDesigner.cond.visibleWhen')">
              <el-input v-model="local.visibleWhen" :placeholder="t('formDesigner.cond.visibleWhenPh')" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.cond.disabledWhen')">
              <el-input v-model="local.disabledWhen" :placeholder="t('formDesigner.cond.disabledWhenPh')" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.cond.requiredWhen')">
              <el-input v-model="local.requiredWhen" :placeholder="t('formDesigner.cond.requiredWhenPh')" @input="emitPatch" />
            </el-form-item>
          </el-form>
        </div>

        <!-- ============ 动态选项源 ============ -->
        <div v-if="needsOptions && canBindQuery" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.optionsSource.section') }}</div>
          <el-form label-position="top" size="small" class="props-form">
            <el-form-item>
              <el-switch
                :model-value="!!local.optionsSource"
                :active-text="t('formDesigner.optionsSource.enable')"
                @change="onToggleOptionsSource"
              />
            </el-form-item>
          </el-form>

          <template v-if="local.optionsSource">
            <p class="props-hint">{{ t('formDesigner.optionsSource.hint') }}</p>
            <el-form label-position="top" size="small" class="props-form">
              <el-form-item :label="t('formDesigner.optionsSource.query')">
                <el-select
                  :model-value="local.optionsSource.queryId"
                  :placeholder="t('formDesigner.optionsSource.queryPh')"
                  filterable
                  style="width: 100%;"
                  @change="(v: number) => patchSource({ queryId: v })"
                >
                  <el-option
                    v-for="q in queryChoices"
                    :key="q.id"
                    :value="q.id"
                    :label="q.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('formDesigner.optionsSource.labelField')">
                <el-input
                  :model-value="local.optionsSource.labelField"
                  :placeholder="t('formDesigner.optionsSource.labelFieldPh')"
                  @input="(v: string) => patchSource({ labelField: v })"
                />
              </el-form-item>
              <el-form-item :label="t('formDesigner.optionsSource.valueField')">
                <el-input
                  :model-value="local.optionsSource.valueField"
                  :placeholder="t('formDesigner.optionsSource.valueFieldPh')"
                  @input="(v: string) => patchSource({ valueField: v })"
                />
              </el-form-item>
              <el-form-item :label="t('formDesigner.optionsSource.cacheSec')">
                <el-input-number
                  :model-value="local.optionsSource.cacheSec"
                  :min="0"
                  :max="300"
                  controls-position="right"
                  style="width: 100%;"
                  @change="(v: number | undefined) => patchSource({ cacheSec: v ?? undefined })"
                />
              </el-form-item>

              <!-- 查询参数 → 绑定表单某个字段的值（与表格的表参数同一套机制） -->
              <template v-if="paramsSchemaOf('optionsSource').length">
                <p class="props-hint">{{ t('formDesigner.sourceParams.hint') }}</p>
                <el-form-item
                  v-for="p in paramsSchemaOf('optionsSource')"
                  :key="'os_' + p.name"
                  :label="p.label || p.name"
                  :required="p.required"
                >
                  <el-select
                    :model-value="paramValue('optionsSource', p.name)"
                    :placeholder="p.placeholder || p.name"
                    filterable
                    allow-create
                    clearable
                    default-first-option
                    style="width: 100%;"
                    @update:model-value="(v: string | undefined) => setParam('optionsSource', p.name, v)"
                  >
                    <el-option v-for="r in paramRefOptions()" :key="r" :value="r" :label="r" />
                  </el-select>
                </el-form-item>
              </template>
            </el-form>
          </template>
        </div>

        <!-- ============ 选项 ============ -->
        <div v-if="needsOptions" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.field.options') }}</div>
          <div class="options-editor">
            <div v-for="(opt, idx) in (local.options || [])" :key="idx" class="option-row">
              <el-input v-model="opt.label" :placeholder="t('formDesigner.option.label')" size="small" style="flex: 1;" @input="emitPatch" />
              <el-input v-model="opt.value" :placeholder="t('formDesigner.option.value')" size="small" style="flex: 1;" @input="emitPatch" />
              <el-button link :icon="Delete" size="small" class="danger-icon" @click="removeOption(idx)" />
            </div>
            <el-button :icon="Plus" size="small" link @click="addOption">{{ t('formDesigner.option.add') }}</el-button>
          </div>
        </div>

        <!-- ============ 栅格 / 样式 ============ -->
        <div v-if="field.type !== 'DIVIDER'" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.style.section') }}</div>
          <el-form label-position="top" size="small" class="props-form">
            <el-form-item :label="t('formDesigner.style.width')">
              <div class="span-row">
                <el-slider
                  :model-value="spanW"
                  :min="1"
                  :max="gridCols"
                  :step="1"
                  show-input
                  :show-input-controls="false"
                  size="small"
                  style="flex: 1;"
                  @update:model-value="(v: any) => patchLayout({ w: Number(v) })"
                />
              </div>
            </el-form-item>
            <el-form-item :label="t('formDesigner.style.height')">
              <el-input-number
                :model-value="field.layout?.h ?? 1"
                :min="1"
                :max="6"
                controls-position="right"
                style="width: 100%;"
                @change="(v: number | undefined) => patchLayout({ h: v ?? 1 })"
              />
            </el-form-item>
            <el-form-item :label="t('formDesigner.style.offset')">
              <div class="span-row">
                <span class="span-sub">{{ t('formDesigner.style.col') }}</span>
                <el-input-number
                  :model-value="field.layout?.x ?? 0"
                  :min="0"
                  :max="Math.max(0, gridCols - (field.layout?.w ?? 1))"
                  controls-position="right"
                  size="small"
                  @change="(v: number | undefined) => patchLayout({ x: v ?? 0 })"
                />
                <span class="span-sub">{{ t('formDesigner.style.row') }}</span>
                <el-input-number
                  :model-value="field.layout?.y ?? 0"
                  :min="0"
                  controls-position="right"
                  size="small"
                  @change="(v: number | undefined) => patchLayout({ y: v ?? 0 })"
                />
              </div>
            </el-form-item>
          </el-form>

          <el-form label-position="top" size="small" class="props-form">
            <el-form-item :label="t('formDesigner.style.background')">
              <el-input
                :model-value="field.style?.background"
                :placeholder="t('formDesigner.style.none')"
                @input="(v: string) => patchStyle({ background: v || undefined })"
              />
            </el-form-item>
            <el-form-item :label="t('formDesigner.style.color')">
              <el-input
                :model-value="field.style?.color"
                :placeholder="t('formDesigner.style.none')"
                @input="(v: string) => patchStyle({ color: v || undefined })"
              />
            </el-form-item>
            <el-form-item :label="t('formDesigner.style.fontSize')">
              <el-input-number
                :model-value="field.style?.fontSize"
                :min="10"
                :max="40"
                controls-position="right"
                style="width: 100%;"
                @change="(v: number | undefined) => patchStyle({ fontSize: v ?? undefined })"
              />
            </el-form-item>
            <el-form-item :label="t('formDesigner.style.align')">
              <el-radio-group
                :model-value="field.style?.align ?? 'left'"
                size="small"
                @update:model-value="(v: any) => patchStyle({ align: v })"
              >
                <el-radio-button value="left">{{ t('formDesigner.style.alignLeft') }}</el-radio-button>
                <el-radio-button value="center">{{ t('formDesigner.style.alignCenter') }}</el-radio-button>
                <el-radio-button value="right">{{ t('formDesigner.style.alignRight') }}</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item :label="t('formDesigner.style.padding')">
              <el-slider
                :model-value="field.style?.padding ?? 0"
                :min="0"
                :max="32"
                show-input
                :show-input-controls="false"
                size="small"
                @update:model-value="(v: any) => patchStyle({ padding: Number(v) })"
              />
            </el-form-item>
            <el-form-item :label="t('formDesigner.style.radius')">
              <el-slider
                :model-value="field.style?.radius ?? 0"
                :min="0"
                :max="24"
                show-input
                :show-input-controls="false"
                size="small"
                @update:model-value="(v: any) => patchStyle({ radius: Number(v) })"
              />
            </el-form-item>
            <el-form-item>
              <el-button text size="small" @click="clearStyle">
                {{ t('formDesigner.style.reset') }}
              </el-button>
            </el-form-item>
          </el-form>
        </div>

        <!-- ============ 表格动态列源 ============ -->
        <div v-if="isTable && canBindQuery" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.tableSource.section') }}</div>
          <el-form label-position="top" size="small" class="props-form">
            <el-form-item>
              <el-switch
                :model-value="!!local.tableSource"
                :active-text="t('formDesigner.tableSource.enable')"
                @change="onToggleTableSource"
              />
            </el-form-item>
          </el-form>

          <template v-if="local.tableSource">
            <p class="props-hint">{{ t('formDesigner.tableSource.hint') }}</p>
            <el-form label-position="top" size="small" class="props-form">
              <el-form-item :label="t('formDesigner.tableSource.query')">
                <el-select
                  :model-value="local.tableSource.queryId"
                  :placeholder="t('formDesigner.tableSource.queryPh')"
                  filterable
                  style="width: 100%;"
                  @change="onTableQueryChange"
                >
                  <el-option
                    v-for="q in queryChoices"
                    :key="q.id"
                    :value="q.id"
                    :label="q.name"
                  />
                </el-select>
              </el-form-item>

              <el-form-item :label="t('formDesigner.tableSource.autoSync')">
                <el-switch
                  :model-value="!!local.tableSource.autoSync"
                  :active-text="t('formDesigner.tableSource.autoSyncOn')"
                  @change="onAutoSyncChange"
                />
              </el-form-item>
              <p class="props-hint">{{ t('formDesigner.tableSource.autoSyncHint') }}</p>

              <el-form-item :label="t('formDesigner.tableSource.cacheSec')">
                <el-input-number
                  :model-value="local.tableSource.cacheSec"
                  :min="0"
                  :max="300"
                  controls-position="right"
                  style="width: 100%;"
                  @change="(v: number | undefined) => patchTableSource({ cacheSec: v ?? undefined })"
                />
              </el-form-item>

              <!-- 查询参数 → 绑定表单某个字段的值 -->
              <template v-if="paramsSchemaOf('tableSource').length">
                <p class="props-hint">{{ t('formDesigner.sourceParams.hint') }}</p>
                <el-form-item
                  v-for="p in paramsSchemaOf('tableSource')"
                  :key="'ts_' + p.name"
                  :label="p.label || p.name"
                  :required="p.required"
                >
                  <el-select
                    :model-value="paramValue('tableSource', p.name)"
                    :placeholder="p.placeholder || p.name"
                    filterable
                    allow-create
                    clearable
                    default-first-option
                    style="width: 100%;"
                    @update:model-value="(v: string | undefined) => setParam('tableSource', p.name, v)"
                  >
                    <el-option v-for="r in paramRefOptions()" :key="r" :value="r" :label="r" />
                  </el-select>
                </el-form-item>
              </template>

              <el-form-item>
                <el-button
                  size="small"
                  :icon="Refresh"
                  :loading="syncingColumns"
                  :disabled="!local.tableSource.queryId"
                  @click="syncTableColumns(true)"
                >
                  {{ t('formDesigner.tableSource.syncNow') }}
                </el-button>
              </el-form-item>
              <p v-if="columnSyncSummary" class="props-hint">{{ columnSyncSummary }}</p>
            </el-form>
          </template>
        </div>

        <!-- ============ 表格列 ============ -->
        <div v-if="isTable" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.table.sectionColumns') }}</div>

          <div class="table-col-switches">
            <el-checkbox v-model="local.requireAllCells" @change="emitPatch">
              {{ t('formDesigner.table.requireAllCells') }}
            </el-checkbox>
          </div>
          <p v-if="!local.requireAllCells" class="props-hint">
            {{ t('formDesigner.table.colRequiredInactiveHint') }}
          </p>

          <div v-for="(col, idx) in (local.columns || [])" :key="idx" class="col-editor">
            <div class="col-row">
              <el-select v-model="col.type" size="small" style="width: 116px;" @change="onColTypeChange(col)">
                <el-option
                  v-for="t in COLUMN_TYPES"
                  :key="t"
                  :value="t"
                  :label="typeLabel(t)"
                />
              </el-select>
              <el-input
                v-model="col.label"
                size="small"
                style="flex: 1;"
                :placeholder="t('formDesigner.table.colTitle')"
                @input="emitPatch"
              />
              <el-button link :icon="Delete" size="small" class="danger-icon" @click="removeColumn(idx)" />
            </div>
            <div class="col-row">
              <el-input
                v-model="col.key"
                size="small"
                style="flex: 1;"
                :placeholder="t('formDesigner.table.colKey')"
                @input="emitPatch"
              />
              <el-input-number
                v-model="col.width"
                size="small"
                :min="60"
                :max="600"
                :controls="false"
                style="width: 78px;"
                :placeholder="t('formDesigner.table.colWidth')"
                @change="emitPatch"
              />
              <el-checkbox v-model="col.required" @change="emitPatch">
                {{ t('formDesigner.table.colRequired') }}
              </el-checkbox>
            </div>

            <!-- select/radio/checkbox 列需要选项 -->
            <div v-if="colNeedsOptions(col)" class="col-options">
              <div v-for="(opt, oi) in (col.options || [])" :key="oi" class="col-option-row">
                <el-input v-model="opt.label" size="small" style="flex: 1;" @input="emitPatch" />
                <el-input v-model="opt.value" size="small" style="flex: 1;" @input="emitPatch" />
                <el-button link :icon="Delete" size="small" class="danger-icon" @click="removeColOption(col, oi)" />
              </div>
              <el-button :icon="Plus" size="small" link @click="addColOption(col)">
                {{ t('formDesigner.option.add') }}
              </el-button>
            </div>
          </div>

          <el-button :icon="Plus" size="small" link @click="addColumn">
            {{ t('formDesigner.table.addColumn') }}
          </el-button>
        </div>

        <!-- ============ 校验 ============ -->
        <div v-if="needsValidator" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.field.validator') }}</div>
          <el-form label-position="top" size="small" class="props-form">
            <el-form-item :label="t('formDesigner.field.min')">
              <el-input-number v-model="local.validator!.min" :placeholder="t('formDesigner.field.minPh')" controls-position="right" style="width: 100%;" @change="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.max')">
              <el-input-number v-model="local.validator!.max" :placeholder="t('formDesigner.field.maxPh')" controls-position="right" style="width: 100%;" @change="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.pattern')">
              <el-input v-model="local.validator!.pattern" :placeholder="t('formDesigner.field.patternPh')" @input="emitPatch" />
            </el-form-item>
            <el-form-item :label="t('formDesigner.field.message')">
              <el-input v-model="local.validator!.message" @input="emitPatch" />
            </el-form-item>
          </el-form>
        </div>

        <!-- ============ 高级 ============ -->
        <div v-if="field.type !== 'DIVIDER'" class="props-section">
          <div class="props-section-label">{{ t('formDesigner.section.advanced') }}</div>
          <el-form label-position="top" size="small" class="props-form">
            <el-form-item>
              <el-checkbox v-model="local.fullWidth" @change="emitPatch">{{ t('formDesigner.field.fullWidth') }}</el-checkbox>
            </el-form-item>
          </el-form>
        </div>
      </div>
    </template>
  </aside>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Document, Delete, Plus, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { COLUMN_TYPES, formDesignApi, type FormFieldSchema, type FieldType, type FormFieldValidator, type FormFieldOption, type FormFieldColumn, type FormFieldLayout, type FormFieldStyle, type FormGridConfig } from '@/api/formDesign';
import { useFormExpr, type ExprIssue, type ExprParamHost } from '@/composables/useFormExpr';
import { GRID_COLS } from '@/utils/form/layout';
import { mergeTableColumns, tableColumnsInSync } from '@/utils/form/tableColumns';
import { designTimeParams } from '@/utils/form/sourceParams';
import { biApi, type BiQueryOption } from '@/api/bi';
import { useAuthStore } from '@/stores/auth';

/**
 * `fields` 用于校验表达式引用是否指向了真实存在的字段。
 * `grid` 提供栅格列数上限（宽度滑杆用）。
 * 不传则跳过对应功能（该面板也可能被复用在只读场景）。
 */
const props = defineProps<{
  field: FormFieldSchema | null;
  fields?: FormFieldSchema[];
  grid?: FormGridConfig;
}>();
const emit = defineEmits<{ patch: [Partial<FormFieldSchema>] }>();
const { t, locale } = useI18n();
const auth = useAuthStore();

const local = reactive<FormFieldSchema>({
  id: '', type: 'TEXT', key: '', label: '',
  required: false, defaultValue: '', placeholder: '', help: '',
  options: undefined, validator: undefined, fullWidth: true,
  columns: undefined, requireAllCells: false,
  visibleWhen: undefined, disabledWhen: undefined, requiredWhen: undefined,
  optionsSource: undefined, tableSource: undefined,
});

const isTable = computed(() => local.type === 'TABLE');

/* ============ 表达式校验 ============ */
const expr = useFormExpr({});

/** 表达式问题（未定义引用 / 语法错误）；只取当前字段的 */
const exprIssues = computed<ExprIssue[]>(() => {
  if (!props.fields || !props.field) return [];
  return expr.issues(props.fields).filter((i) => i.fieldId === props.field!.id);
});

/** 可被引用的字段 key，供用户手写表达式时对照 */
const refCandidates = computed(() => {
  if (!props.fields) return [];
  return props.fields.filter((f) => f.key && f.id !== props.field?.id).map((f) => f.key);
});

/** 问题文案在此组装 —— issue 只带结构化数据，中文/英文都由 i18n 决定 */
function issueText(issue: ExprIssue): string {
  return issue.kind === 'undefined-ref'
    ? t('formDesigner.expr.undefinedRef', { key: issue.detail })
    : issue.detail;
}

/* ============ 栅格 / 样式 ============ */

const gridCols = computed(() => props.grid?.cols ?? GRID_COLS);

/** 宽度滑杆的当前值 */
const spanW = computed(() => props.field?.layout?.w ?? gridCols.value);

function patchLayout(patch: Partial<FormFieldLayout>) {
  if (!props.field) return;
  emit('patch', {
    layout: { x: 0, y: props.field.layout?.y ?? 0, w: gridCols.value, h: 1, ...props.field.layout, ...patch },
  });
}

/** 局部更新 style；空串一律转 undefined，避免存下一堆 "" 让「未设置」和「设为空」分不清 */
function patchStyle(patch: Partial<FormFieldStyle>) {
  if (!props.field) return;
  const next: FormFieldStyle = { ...(props.field.style ?? {}) };
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined || v === '') delete next[k as keyof FormFieldStyle];
    else (next as any)[k] = v;
  }
  emit('patch', { style: next });
}

function clearStyle() {
  emit('patch', { style: undefined });
}

/* ============ 动态选项源 ============ */

/**
 * 候选查询列表要调 `/bi/data/queries`，那个接口要求 `bi:dataset:view`。
 * 没有这个权限的设计者根本配不了动态选项 —— 与其给他一个点开就 403 的空下拉，
 * 不如整块不显示。
 */
const canBindQuery = computed(() => auth.hasPerm('bi:dataset:view'));
const queryChoices = ref<BiQueryOption[]>([]);

onMounted(async () => {
  if (!canBindQuery.value) return;
  try {
    queryChoices.value = await biApi.queryOptions();
  } catch {
    // 拉不到就留空数组：用户仍可手填 queryId/列名，只是没有下拉提示
    queryChoices.value = [];
  }
});

/** 开关切换：关掉时整个 optionsSource 置空，而不是留着半截配置 */
function onToggleOptionsSource(on: boolean | string | number) {
  // queryId 先占位为 0：后端保存时会校验 > 0，用户没选完就保存会得到明确报错，
  // 比静默存个 null 强。
  local.optionsSource = on
    ? { queryId: 0, labelField: '', valueField: '' }
    : undefined;
  emitPatch();
}

/** 局部更新 optionsSource（保留其它已填的字段） */
function patchSource(patch: Partial<NonNullable<FormFieldSchema['optionsSource']>>) {
  local.optionsSource = {
    ...(local.optionsSource ?? { queryId: 0, labelField: '', valueField: '' }),
    ...patch,
  };
  emitPatch();
}

watch(() => props.field, (f) => {
  if (!f) return;
  Object.assign(local, JSON.parse(JSON.stringify(f)));
  if (!local.validator) local.validator = { min: undefined, max: undefined, pattern: '', message: '' } as FormFieldValidator;
  /*
   * 内联类型判断，不引用下方 needsOptions —— 因为 watch 用 { immediate: true }
   * 在 setup 阶段会同步立即执行一次 callback（当 props.field 已非 null 时），
   * 而 needsOptions 是 const 声明，在 callback 执行时还没初始化（TDZ），
   * 会抛 "Cannot access 'needsOptions' before initialization"。
   * 模板里的 v-if="needsOptions" 仍正常使用（computed 在 mount 时已就绪）。
   */
  if (!local.options && (local.type === 'SELECT' || local.type === 'RADIO' || local.type === 'CHECKBOX')) {
    local.options = [] as FormFieldOption[];
  }
}, { immediate: true });

const needsOptions = computed(() =>
  local.type === 'SELECT' || local.type === 'RADIO' || local.type === 'CHECKBOX'
);
const needsValidator = computed(() =>
  local.type === 'TEXT' || local.type === 'TEXTAREA' || local.type === 'NUMBER'
);

function typeLabel(type: FieldType): string {
  return t(`formDesigner.type.${type}`);
}

function addOption() {
  if (!local.options) local.options = [];
  local.options.push({ label: '', value: '' });
  emitPatch();
}

function removeOption(idx: number) {
  if (!local.options) return;
  local.options.splice(idx, 1);
  emitPatch();
}

// ===================== 表格列编辑 =====================

function colNeedsOptions(col: FormFieldColumn): boolean {
  return col.type === 'SELECT' || col.type === 'RADIO' || col.type === 'CHECKBOX';
}

/* ============ 表格动态列源 ============ */

const syncingColumns = ref(false);
const columnSyncSummary = ref('');

/** 开关切换：关掉时整个 tableSource 置空，而不是留着半截配置 */
function onToggleTableSource(on: boolean | string | number) {
  // queryId 先占位为 0：后端保存时会校验 > 0，用户没选完就保存会得到明确报错，
  // 比静默存个 null 强。autoSync 默认关 —— 同步会删列，不该在后台悄悄做。
  local.tableSource = on ? { queryId: 0, autoSync: false } : undefined;
  columnSyncSummary.value = '';
  emitPatch();
}

/** 局部更新 tableSource（保留其它已填的字段） */
function patchTableSource(patch: Partial<NonNullable<FormFieldSchema['tableSource']>>) {
  local.tableSource = { ...(local.tableSource ?? { queryId: 0 }), ...patch };
  emitPatch();
}

/* ============ 查询参数绑定到字段值 ============ */

/**
 * 当前所选查询的参数定义。
 *
 * 来源是 `biApi.queryOptions()` 返回的 paramsSchema —— 那条列表接口本来就带
 * params_schema，不用为了填参数再单独拉一次查询详情。
 */
function paramsSchemaOf(host: ExprParamHost) {
  const qid = host === 'optionsSource' ? local.optionsSource?.queryId : local.tableSource?.queryId;
  if (!qid) return [] as NonNullable<BiQueryOption['paramsSchema']>;
  return queryChoices.value.find((q) => q.id === qid)?.paramsSchema ?? [];
}

/** 可绑定的字段引用，形如 `{{ $form.xxx }}` —— 与表达式区用的是同一批候选 */
function paramRefOptions(): string[] {
  return refCandidates.value.map((k) => `{{ $form.${k} }}`);
}

function paramValue(host: ExprParamHost, name: string): string {
  const params = host === 'optionsSource' ? local.optionsSource?.params : local.tableSource?.params;
  return params?.[name] ?? '';
}

function setParam(host: ExprParamHost, name: string, v: string | undefined) {
  // 两个分支各自收窄 —— 先取 union 再回写会让 TS 认不出该赋给哪个类型
  const params: Record<string, string> = {
    ...(host === 'optionsSource' ? local.optionsSource?.params : local.tableSource?.params),
  };
  // 清空就删键而不是存空串："" 与「没配过」在存下来的 schema 里必须能分辨
  if (v === '' || v === undefined) delete params[name];
  else params[name] = v;

  if (host === 'optionsSource') {
    if (!local.optionsSource) return;
    local.optionsSource = { ...local.optionsSource, params };
  } else {
    if (!local.tableSource) return;
    local.tableSource = { ...local.tableSource, params };
  }
  emitPatch();
}

function onAutoSyncChange(on: boolean | string | number) {
  patchTableSource({ autoSync: !!on });
}

/**
 * 换查询是显式动作 —— 一定同步一次，不用管 autoSync 开关。
 * 不然用户刚绑完查询却发现列还是老的，会以为功能没生效。
 */
async function onTableQueryChange(v: number) {
  patchTableSource({ queryId: v });
  await syncTableColumns();
}

/**
 * 把参数名拼成当前语言的列表写法（「a、b」/「a, b」）。
 *
 * 两个不用现成方案的理由：
 * 1. vue-i18n 11 对 `{names}` 传数组是按普通插值处理，渲染出来是 `["a","b"]` 这种 JSON；
 * 2. `Intl.ListFormat` 的 zh-CN + `unit` 风格在 Node 的 ICU 下直接不给分隔符
 *    （format(['a','b']) 得到 "ab"），而浏览器 ICU 版本不同结果可能又不一样 ——
 *    同一句话在不同机器上长得不一样，比多写一个分隔符表糟糕得多。
 */
const LIST_SEP: Record<string, string> = {
  'zh-CN': '、', 'zh-TW': '、', 'zh-HK': '、', ja: '、', ko: ', ',
};

function listNames(names: string[]): string {
  const key = locale.value ?? '';
  const sep = LIST_SEP[key] ?? LIST_SEP[key.split('-')[0]] ?? ', ';
  return names.join(sep);
}

/**
 * 拉查询列并做**选择性同步**。
 *
 * @param manual 用户点了「立即同步」。即使判定已经对上也照样跑并回报结果 ——
 *               点了按钮不给反馈是最容易被当成「点了没反应」的地方。
 */
async function syncTableColumns(manual = false): Promise<void> {
  const src = local.tableSource;
  if (!src || !src.queryId) return;

  const { send, skipped } = designTimeParams(src.params);

  syncingColumns.value = true;
  columnSyncSummary.value = '';
  try {
    const incoming = (await formDesignApi.tableColumns({ ...src, params: send })) || [];

    if (!manual && tableColumnsInSync(local.columns, incoming)) {
      columnSyncSummary.value = t('formDesigner.tableSource.alreadyInSync');
      return;
    }

    const r = mergeTableColumns(local.columns, incoming);
    local.columns = r.columns;
    columnSyncSummary.value = t('formDesigner.tableSource.syncResult', {
      added: r.added.length,
      removed: r.removed.length,
      kept: r.kept.length,
    });
    emitPatch();
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message || String(e);
    columnSyncSummary.value = t('formDesigner.tableSource.syncFailed', { msg })
      + (skipped.length ? t('formDesigner.tableSource.syncSkipped', { names: listNames(skipped) }) : '');
  } finally {
    syncingColumns.value = false;
  }
}

/**
 * 选了表格字段且开了动态更新 → 自动补齐一次。
 *
 * 挂在 field 的 watch 上而不是 Designer 的 load 上：同步只会新增/删除列，
 * 用户切走再切回来就等于「重新看一眼对不对得上」，正好是 autoSync 想要的时机；
 * 而每次进设计器都对所有表格发请求，在只有一两个表格时纯属浪费。
 */
watch(
  () => [props.field?.id, props.field?.tableSource?.queryId, props.field?.tableSource?.autoSync] as const,
  ([id, queryId, autoSync]) => {
    columnSyncSummary.value = '';
    if (!id || !autoSync || !queryId) return;
    void syncTableColumns();
  },
);

function addColumn() {
  if (!local.columns) local.columns = [];
  local.columns.push({ key: '', label: '', type: 'TEXT', required: false });
  emitPatch();
}

function removeColumn(idx: number) {
  if (!local.columns) return;
  local.columns.splice(idx, 1);
  emitPatch();
}

/** 切换列类型：切成选择类要补 options 数组，切走时留着无害（后端只对需要时校验） */
function onColTypeChange(col: FormFieldColumn) {
  if (colNeedsOptions(col) && !col.options) {
    col.options = [] as FormFieldOption[];
  }
  emitPatch();
}

function addColOption(col: FormFieldColumn) {
  if (!col.options) col.options = [];
  col.options.push({ label: '', value: '' });
  emitPatch();
}

function removeColOption(col: FormFieldColumn, idx: number) {
  if (!col.options) return;
  col.options.splice(idx, 1);
  emitPatch();
}

function emitPatch() {
  emit('patch', { ...local });
}
</script>

<style scoped>
.form-field-props {
  width: 100%;
  background: var(--color-bg-elev);
  border-left: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.props-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
  padding: 60px 16px;
  font-size: 13px;
}
.empty-icon { font-size: 36px; color: var(--color-text-muted); margin-bottom: 12px; }

.props-title {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 12px 12px 10px;
  border-bottom: 1px solid var(--color-border-soft);
}
.props-title-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.props-title-text::before {
  content: '';
  display: inline-block;
  width: 4px;
  height: 14px;
  background: var(--color-primary);
  border-radius: 2px;
}
.props-title-type {
  font-size: 11px;
  padding: 1px 6px;
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: 2px;
}

.props-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0 16px;
}
.props-section { padding: 8px 12px 4px; }
.props-section-label {
  font-size: 10px;
  font-weight: 600;
  color: var(--color-text-muted);
  margin: 4px 0 8px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.props-form :deep(.el-form-item) { margin-bottom: 14px; }

.options-editor { padding: 0; }
.option-row {
  display: flex;
  gap: 4px;
  margin-bottom: 6px;
  align-items: center;
}
.danger-icon :deep(.el-icon) { color: var(--color-danger); }

/* ===== 表格列编辑 ===== */
.table-col-switches { margin-bottom: 6px; }

/* ===== 表达式 ===== */
.expr-issue { margin-bottom: 6px; }
.expr-refs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}
.expr-refs-label { font-size: 11px; margin-right: 2px; }
.expr-ref-chip {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--radius-xs);
  background: var(--color-bg);
  border: 1px solid var(--color-border-soft);
  color: var(--color-text-secondary);
  cursor: pointer;
}
.expr-ref-chip:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

/* ===== 栅格 / 样式 ===== */
.span-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.span-sub {
  font-size: 12px;
  color: var(--color-text-muted);
  flex: none;
}

.props-hint {
  margin: 0 0 8px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-muted);
}
.col-editor {
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-md, 4px);
  padding: 6px;
  margin-bottom: 8px;
  background: var(--color-bg);
}
.col-row {
  display: flex;
  gap: 4px;
  align-items: center;
  margin-bottom: 4px;
}
.col-row:last-child { margin-bottom: 0; }
.col-options {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--color-border-soft);
}
.col-option-row {
  display: flex;
  gap: 4px;
  align-items: center;
  margin-bottom: 4px;
}
</style>