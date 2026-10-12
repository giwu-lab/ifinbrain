<script setup lang="ts">
/**
 * 报表查询头的**字段组**。
 *
 * <p>按 sys_query.params_schema 动态生成表单控件 —— 类型 string/number/date/datetime/select
 * 五种（与后端 QueryParamSchema + 报表的控件覆盖对应，不擅自扩展类型体系）。
 *
 * <p><b>本组件只渲染字段，不渲染标题行和按钮</b>。父页面（播放页）用
 * {@code <div class="toolbar">} 包住本组件 + {@code <div class="toolbar-right">}，
 * 于是查询区与发票列表是同一套骨架（label 在左的横排字段 + 右侧按钮组）。
 * 根节点用 {@code display: contents} 让字段直接成为 .toolbar 的 flex 子项 ——
 * 否则组件根会占掉一个 flex 格，字段被挤进「一个巨大的 flex item」里对不齐。
 *
 * <p>两个刻意的取舍：
 * 1. **查询按钮归父组件** —— 底部那排按钮夹在字段和视线之间，字段多的时候
 *    得先滚到底才看得见它；放在工具栏右端，第一屏就能看见。
 * 2. **没有「重置」按钮，改成每个填写框右侧的清除 icon** —— 重置是全量动作，
 *    但实际只想清掉其中一两个字段；而每个框右边的 × 就在手边，且 el-input / el-select /
 *    el-date-picker 都能原生支持，只有数字框需要自己补一个后缀。
 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { Close } from '@element-plus/icons-vue';
import type { HeaderFieldCfg, ReportParamSchema } from '@/api/report';
import { controlOf, optionsOf, placeholderOf } from '@/utils/report/header';

const props = withDefaults(defineProps<{
  params: ReportParamSchema[];
  modelValue: Record<string, string>;
  /** 每个字段的控件覆盖（设计器配的）。缺失 = 跟随 schema 自带 type */
  fields?: Record<string, HeaderFieldCfg> | null;
  /** 折叠时只显示前 4 个 */
  collapsed?: boolean;
  loading?: boolean;
  disabled?: boolean;
}>(), {
  fields: null,
  collapsed: false,
  loading: false,
  disabled: false,
});

const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, string>): void;
  (e: 'search'): void;
}>();

const { t } = useI18n();

/** 折叠时只显示前 4 个 —— 阈值由父页面的 COLLAPSE_AT 约定（两处必须一致） */
const COLLAPSE_AT = 4;
const shown = computed(() =>
  props.collapsed ? props.params.slice(0, COLLAPSE_AT) : props.params
);

/**
 * 渲染列表：把「参数 + 控件类型 + 候选值 + 占位符」预先摊平成一层。
 *
 * 模板里只做 v-if 分发，不再自己判断 p.type —— 控件解析收敛到 utils 的 controlOf，
 * 与设计器用的是同一个函数，杜绝两处推导不一致。
 */
const rendered = computed(() =>
  shown.value.map((p) => ({
    p,
    ctrl: controlOf(p, props.fields),
    placeholder: placeholderOf(p, props.fields),
    options: optionsOf(p, props.fields),
    // 清除 icon 只在真的有值时出现：空框上挂个 × 是噪声
    filled: !!(props.modelValue[p.name] ?? '').toString().trim(),
  }))
);

function setValue(name: string, v: string | number | Date | null | undefined) {
  // el-date-picker 在清空时给 null；统一落成空串，避免把 null 塞进请求体
  const val = v === null || v === undefined ? '' : String(v);
  emit('update:modelValue', { ...props.modelValue, [name]: val });
}

function onEnter(e: Event) {
  if ((e as KeyboardEvent).key === 'Enter') emit('search');
}
</script>

<template>
  <!--
    display: contents —— 让 .field 直接成为父级 .toolbar 的 flex 子项，
    这样它们和右侧 .toolbar-right 处在同一行；根节点若占位会多出一层包裹。
  -->
  <div class="rp-header-fields">
    <span v-for="f in rendered" :key="f.p.name" class="field">
      <span class="label">
        {{ f.p.label || f.p.name }}<span v-if="f.p.required" class="rp-required">*</span>：
      </span>
      <!--
        下拉框只传字符串，不引入新的值域 —— 后端参数类型转换逻辑一行都不用改。
        filterable 让候选值多时仍能快速定位；clearable 是内建的清除 icon。
      -->
      <el-select
        v-if="f.ctrl === 'select'"
        :model-value="modelValue[f.p.name] || undefined"
        :placeholder="f.placeholder || t('report.pickOption')"
        :disabled="disabled"
        class="rp-ctrl"
        clearable
        filterable
        @update:model-value="(v: any) => setValue(f.p.name, v)"
      >
        <el-option v-for="o in f.options" :key="o" :label="o" :value="o" />
      </el-select>
      <el-input-number
        v-else-if="f.ctrl === 'number'"
        :model-value="modelValue[f.p.name] === '' || modelValue[f.p.name] == null ? undefined : Number(modelValue[f.p.name])"
        :placeholder="f.placeholder"
        :disabled="disabled"
        class="rp-ctrl rp-ctrl-num"
        controls-position="right"
        @update:model-value="(v: any) => setValue(f.p.name, v)"
        @keyup.enter="onEnter"
      >
        <!-- el-input-number 没有 clearable，用后缀补一个清除 icon -->
        <template v-if="f.filled && !disabled" #suffix>
          <el-icon class="rp-clear" @click="setValue(f.p.name, '')">
            <Close />
          </el-icon>
        </template>
      </el-input-number>
      <el-date-picker
        v-else-if="f.ctrl === 'date' || f.ctrl === 'datetime'"
        :model-value="modelValue[f.p.name] || null"
        :type="f.ctrl === 'date' ? 'date' : 'datetime'"
        :value-format="f.ctrl === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss'"
        :placeholder="f.placeholder || t('report.pickDate')"
        :disabled="disabled"
        class="rp-ctrl"
        clearable
        @update:model-value="(v: any) => setValue(f.p.name, v)"
      />
      <el-input
        v-else
        :model-value="modelValue[f.p.name] ?? ''"
        :placeholder="f.placeholder"
        :disabled="disabled"
        class="rp-ctrl"
        clearable
        @update:model-value="(v: string) => setValue(f.p.name, v)"
        @keyup.enter="onEnter"
      />
    </span>
  </div>
</template>

<style scoped>
.rp-header-fields {
  display: contents;
}

/* 控件定宽：横排字段靠固定宽度对齐，和发票列表的查询区同一套节奏。
   数字框窄一点 —— 它右边的 +/- 按钮会占掉约 34px。 */
.rp-ctrl {
  width: 200px;
}
.rp-ctrl-num {
  width: 180px;
}

/* 必填星号：跟 query-page 的 .required 同色，这里用 token 而不是硬编码 */
.rp-required {
  color: var(--color-danger);
  margin: 0 2px;
}

/* 数字框后缀里的清除 icon：跟输入区一个视觉层次，别做成抢戏的主色 */
.rp-clear {
  cursor: pointer;
  color: var(--color-text-muted);
}

.rp-clear:hover {
  color: var(--color-text-secondary);
}
</style>
