<!--
  DynamicFormRenderer —— 按 fields schema 渲染完整表单。
  props:
    - fields: 字段列表
    - modelValue: 表单数据 Record<string, any>
    - readonly?: 是否只读
    - labelPosition?: 'top' | 'left'  (默认 'top')
    - labelWidth?: string             (默认 '100px'，与 AGENTS.md §7 一致)
  emit:
    - update:modelValue
    - submit: 用户按底栏提交按钮（若有 prop showSubmit:true）
-->
<template>
  <el-form
    ref="formRef"
    :model="formData"
    :rules="designMode ? {} : rules"
    :validate-on-rule-change="false"
    :label-position="labelPosition"
    :label-width="labelWidth"
    :disabled="readonly"
    class="dynamic-form-renderer"
    @submit.prevent
  >
    <div class="form-grid" :style="gridStyle">
      <template v-for="(item, idx) in renderFields" :key="item.f.id || item.rf.key">
        <!-- rendered = visible || designMode：
             visibleWhen 为 false 的字段整块不渲染：
             值仍保留在 formData 里（用户把条件改回来就不用重填），
             但不参与校验 —— 隐藏字段还强制必填会让表单永远提交不了。
             设计器画布例外：隐藏字段照样要显示，否则没法编辑。 -->
        <div
          v-if="item.rendered"
          class="form-cell"
          :style="item.placement"
        >
          <!-- divider 不参与 form-item，仅占整行 -->
          <div v-if="item.f.type === 'DIVIDER'" class="divider-wrap">
            <component
              :is="resolveField(item.f.type)"
              :field="item.rf"
              :readonly="readonly"
            />
          </div>
          <el-form-item
            v-else
            :prop="item.f.key"
            :required="item.required"
            :error="designMode ? undefined : errorOf(item.f.key)"
            :label-width="item.style?.labelWidth"
            :label-align="item.style?.labelAlign"
          >
            <template #label>
              <span class="field-label" :style="item.labelStyle">{{ item.rf.label }}</span>
              <span v-if="item.required" class="required-mark">*</span>
              <el-tooltip v-if="item.rf.help" :content="item.rf.help" placement="top">
                <el-icon class="help-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <div class="cell-body" :style="item.bodyStyle">
              <component
                :is="resolveField(item.f.type)"
                :field="item.rf"
                :model-value="formData[item.f.key]"
                :readonly="readonly || item.disabled"
                @update:model-value="(v: any) => updateField(item.f.key, v)"
              />
            </div>
          </el-form-item>

          <!-- 设计器 chrome 的挂载点。运行态不传这个插槽，
               所以这里是真正的零成本、零 DOM —— 渲染路径完全不变。 -->
          <slot name="cell" :field="item.f" :resolved="item.rf" :item="item" :index="idx" />
        </div>
      </template>
    </div>

    <div v-if="showSubmit" class="form-actions">
      <el-button @click="onCancel">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :icon="Check" @click="onSubmit">{{ t('common.submit') }}</el-button>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { QuestionFilled, Check } from '@element-plus/icons-vue';
import { resolveField } from './fieldRegistry';
import { useFormExpr } from '@/composables/useFormExpr';
import { toGridPlacement, migrateFields, DEFAULT_GRID } from '@/utils/form/layout';
import type { FormFieldSchema, FormGridConfig } from '@/api/formDesign';

const props = withDefaults(defineProps<{
  fields: FormFieldSchema[];
  modelValue?: Record<string, any>;
  readonly?: boolean;
  showSubmit?: boolean;
  labelPosition?: 'top' | 'left';
  labelWidth?: string;
  /** 栅格配置；不传用 24 列 / 64px 行高 / 16px 间距 */
  grid?: FormGridConfig;
  /**
   * 设计器画布模式。打开后
   * 1) visibleWhen 不成立的字段照常渲染（设计师必须能编辑隐藏字段）
   * 2) cell 具名插槽透出，FormCanvas 在上面挂拖拽/操作 chrome
   * 运行态（designMode 不为真）这两条都不生效；插槽不传则完全不渲染。
   */
  designMode?: boolean;
}>(), {
  modelValue: () => ({}),
  readonly: false,
  showSubmit: false,
  labelPosition: 'top',
  labelWidth: '100px',
  designMode: false,
});

const emit = defineEmits<{
  'update:modelValue': [Record<string, any>];
  submit: [Record<string, any>];
  cancel: [];
}>();

const { t } = useI18n();

const formRef = ref();
const errorMap = reactive<Record<string, string>>({});

const formData = reactive<Record<string, any>>({ ...props.modelValue });

/**
 * 表达式求值器。`$form` 就是 formData 本身。
 *
 * 响应式是白拿的：expr.resolve() 内部经沙箱 Proxy 读到 `$form.<key>` 时，
 * 这次读取会被 Vue 依赖追踪自动记下 → 用户改任一输入框，所有引用它的
 * label / placeholder / help 自动重算。详见 composables/useFormExpr.ts 头注释。
 */
const expr = useFormExpr(formData);

/**
 * 栅格容器：24 列 + 固定行高。
 *
 * 用 CSS Grid 而非绝对定位 —— 百分比 + 栅格天然响应式，
 * deviceMode 切到 768 / 375 时字段自动按比例缩窄，不需要任何适配代码。
 */
const gridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${props.grid?.cols ?? 24}, minmax(0, 1fr))`,
  /*
   * minmax(行高, auto) 而不是固定 px。
   *
   * 固定行高是「每格都一样高」的假设，但富文本(rows=6 ≈ 124px)、
   * 多行文本、明细表格都会超过 rowHeight。固定轨道被撑不破，
   * 内容就从轨道溢出去画到画布外面 —— 表现为画布底边/侧边出现错位和缺口。
   * 轨道可增长后，行高仍是 rowHeight（短字段不变），长字段自己把行撑开。
   */
  gridAutoRows: `minmax(${props.grid?.rowHeight ?? 64}px, auto)`,
  gap: `${props.grid?.gap ?? 16}px`,
}));

/**
 * 栅格坐标。
 *
 * 老 schema（迁移前保存的、或建好还没保存过的）只有 `fullWidth`，没有 `layout`。
 * 设计器 store 在 reset() 里跑过 migrateFields，所以设计器里看不出问题；
 * 但运行态预览、流程取数、报销弹窗这些**直接吃接口数据**的入口拿到的仍是老 schema，
 * 渲染层一旦 fallback 成 `1 / -1`，所有半宽字段就会变成满宽 —— 和设计器对不上。
 *
 * 所以兜底放在渲染层：缺 layout 就按同一套 fullWidth 语义补出来。
 * 一次性算完（computed 缓存），不会每帧重跑。
 */
const laidOutFields = computed(() => migrateFields(props.fields, props.grid ?? DEFAULT_GRID));

/**
 * 渲染用字段列表。
 *
 * 每个字段解析出运行时状态 + 样式，都是**表达式求值结果**，
 * 在这个 computed 里一次算完 —— 模板里直接读。
 *
 * rf 是文本表达式已解析的字段对象（无文本表达式时与 f 同引用，零开销）。
 */
const renderFields = computed(() =>
  laidOutFields.value.map((f) => {
    const st = f.style;
    const visible = expr.isVisible(f);
    return {
      f,
      rf: expr.resolve(f),
      style: st,
      visible,
      // 设计器要看见隐藏字段，运行态要藏起来 —— 两者共用同一份解析结果
      rendered: visible || props.designMode,
      disabled: expr.isDisabled(f),
      required: expr.isRequired(f),
      // laidOutFields 保证每个字段都有 layout，这里不再需要满宽兜底
      placement: toGridPlacement(f.layout!, props.grid),
      labelStyle: {
        color: st?.labelColor,
        fontSize: st?.fontSize ? `${st.fontSize}px` : undefined,
      },
      bodyStyle: {
        background: st?.background,
        color: st?.color,
        padding: st?.padding != null ? `${st.padding}px` : undefined,
        borderRadius: st?.radius != null ? `${st.radius}px` : undefined,
        border: st?.border,
        textAlign: st?.align,
        fontSize: st?.fontSize ? `${st.fontSize}px` : undefined,
      },
    };
  }),
);

/** 校验只看可见字段：隐藏字段还强制必填会让表单永远提交不了 */
const visibleFields = computed(() => renderFields.value.filter((i) => i.visible));

// 每次 props.modelValue 变化时，同步本地 formData（保留已有字段）
watch(() => props.modelValue, (v) => {
  for (const k of Object.keys(formData)) delete formData[k];
  Object.assign(formData, v);
}, { deep: true });

// 初始化：把每个字段的 defaultValue 填入
watch(() => props.fields, () => {
  for (const f of props.fields) {
    if (formData[f.key] == null && f.defaultValue != null && f.defaultValue !== '') {
      // TABLE 没有字符串型默认值（值是对象数组），DIVIDER 本就无值
      if (f.type === 'TABLE' || f.type === 'DIVIDER') continue;
      formData[f.key] = f.type === 'NUMBER' ? Number(f.defaultValue) : f.defaultValue;
    }
  }
}, { immediate: true });

/** 单元格「空」的判定：null / undefined / 空白串 / 空数组都算未填 */
function isBlankCell(v: any): boolean {
  if (v == null) return true;
  if (typeof v === 'string') return v.trim() === '';
  if (Array.isArray(v)) return v.length === 0;
  return false;
}

/**
 * 表格的两个正交必填开关（与属性面板一一对应）：
 *
 * <pre>
 *   required | requireAllCells | 0 行      | 有行但缺必填列
 *   ---------+-----------------+-----------+------------------
 *    false   | false           | 通过      | 通过
 *    true    | false           | 报错      | 通过
 *    false   | true            | 通过      | 报错
 *    true    | true            | 报错      | 报错
 * </pre>
 *
 * 注意「有行但缺必填列」在 requireAllCells 为 false 时是**通过**的 —— 列级 required
 * 只有开了这个开关才生效，否则配了等于没配。
 */
function tableValidator(field: FormFieldSchema) {
  return (_r: unknown, value: any, cb: (e?: Error) => void) => {
    const rows = Array.isArray(value) ? value : [];
    if (rows.length === 0) {
      return field.required
        ? cb(new Error(t('formDesigner.table.errAtLeastOneRow', { label: field.label })))
        : cb();
    }
    if (!field.requireAllCells) return cb();
    const requiredCols = (field.columns || []).filter((c) => c.required && c.key);
    for (let i = 0; i < rows.length; i++) {
      for (const c of requiredCols) {
        if (isBlankCell(rows[i]?.[c.key])) {
          return cb(new Error(t('formDesigner.table.errCellRequired', {
            row: i + 1,
            label: c.label || c.key,
          })));
        }
      }
    }
    cb();
  };
}

/**
 * 校验规则。
 *
 * 两个关键点：
 * 1. 只对**可见字段**建规则（visibleFields）。隐藏字段留着必填规则的话，
 *    表单会因为一个用户根本看不见的字段而永远提交不了。
 * 2. required 取 `expr.isRequired()` —— 字段级 required 与 requiredWhen 任一为真即必填。
 * 规则对象整体是 computed，条件一变规则立即跟着变。
 */
const rules = computed(() => {
  const r: Record<string, any[]> = {};
  for (const { f, required } of visibleFields.value) {
    if (f.type === 'DIVIDER') continue;
    const itemRules: any[] = [];
    const label = expr.text(f.label, f.key);
    if (f.type === 'TABLE') {
      // 表格不用通用的 required 规则：空数组要区分「允许留空」与「必须填一行」
      if (required || f.requireAllCells || (f.columns || []).some((c) => c.required)) {
        itemRules.push({ validator: tableValidator(f), trigger: ['blur', 'change'] });
      }
    } else if (required) {
      itemRules.push({ required: true, message: t('formDesigner.required', { label }), trigger: 'blur' });
    }
    if (f.validator?.pattern && (f.type === 'TEXT' || f.type === 'TEXTAREA')) {
      itemRules.push({ pattern: new RegExp(f.validator.pattern), message: f.validator.message || t('formDesigner.invalid'), trigger: 'blur' });
    }
    if (itemRules.length) r[f.key] = itemRules;
  }
  return r;
});


function updateField(key: string, value: any) {
  formData[key] = value;
  emit('update:modelValue', { ...formData });
}

function errorOf(key: string) {
  return errorMap[key];
}

async function onSubmit() {
  if (!formRef.value) return;
  try {
    await formRef.value.validate();
    emit('submit', { ...formData });
  } catch (errors: any) {
    // 收集错误到 errorMap（el-form-item 同步显示）
    if (errors && typeof errors === 'object') {
      for (const k of Object.keys(errorMap)) delete errorMap[k];
      for (const [key, val] of Object.entries(errors)) {
        const msg = (val as any[])?.[0]?.message;
        if (msg) errorMap[key] = msg;
      }
    }
  }
}

function onCancel() {
  emit('cancel');
}

defineExpose({ validate: () => formRef.value?.validate(), formData });
</script>

<style scoped>
.dynamic-form-renderer { width: 100%; }

/*
 * 24 列栅格。列/行/间距由 gridStyle 内联给出（来自表单级配置），
 * 这里只负责让单元格撑满并避免内容把格子顶开。
 * min-width:0 很重要 —— grid 子项默认 min-width:auto，长文本
 * （如超长 placeholder）会把格子撑破导致整行错位。
 */
.form-grid {
  display: grid;
  align-items: start;
}
.form-cell {
  min-width: 0;
  /* 设计器在格子上挂绝对定位的覆盖层（拖拽热区 / 操作栏 / 手柄）。
     运行态没有覆盖层，position:relative 无副作用。 */
  position: relative;
}
/* 单元格内的 el-form-item 自带 margin-bottom，这里收掉，
   间距统一由栅格 gap 控制，否则会出现「行间距 + 字段间距」两层叠加 */
.form-cell :deep(.el-form-item) {
  margin-bottom: 0;
}
/*
 * el-form-item__content 是 flex 容器，.cell-body 作为 flex item 默认
 * flex:0 1 auto = 按内容宽度收缩。结果是 el-input/el-select 的 width:100%
 * 去解一个内容宽度，控件只占 intrinsic 宽度（输入框 ~140px、下拉 ~30px），
 * 完全撑不满 12 列的格子。flex:1 让它先吃满再让控件的 100% 生效。
 */
.cell-body { flex: 1; min-width: 0; }

.field-label { color: var(--color-text); }
.required-mark { color: var(--color-danger); margin-left: 2px; }
.help-icon { margin-left: 4px; font-size: 12px; color: var(--color-text-muted); vertical-align: middle; }
.divider-wrap { width: 100%; }
.form-actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border-soft);
}
</style>