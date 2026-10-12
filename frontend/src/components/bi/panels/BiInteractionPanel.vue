<script setup lang="ts">
/**
 * 联动配置：把「源组件的点击分类」写进「本组件的某个查询参数」。
 *
 * 例：柱状图按月份画，点击 3 月 → 明细表的 month 参数被写成 '3' → 重新取数。
 *
 * 参数下拉来自本组件绑定的 sys_query 的 params_schema，所以必须先绑数据源
 * —— 没绑时这一段显示提示而不是给一个空下拉（选了也没地方写）。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Delete } from '@element-plus/icons-vue';
import type { BiQueryOption, BiWidget, WidgetLink } from '@/api/bi';
import { biApi } from '@/api/bi';

const props = defineProps<{
  widget: BiWidget;
  /** 画布上全部组件，用来选触发源 */
  widgets: BiWidget[];
  /** 当前生效的运行时筛选（只读展示 + 清除入口） */
  active: { param: string; value: string }[];
}>();

const emit = defineEmits<{
  (e: 'patch', patch: Partial<BiWidget>): void;
  (e: 'clear-filters'): void;
}>();

const { t } = useI18n();

const queries = ref<BiQueryOption[]>([]);
const draft = ref<{ from: string; param: string }>({ from: '', param: '' });

const links = computed<WidgetLink[]>(() => props.widget.interact?.on || []);

/** 本组件的查询可选参数名 —— 联动值最终要落到这里 */
const paramNames = computed<string[]>(() => {
  const q = queries.value.find((x) => x.id === props.widget.data?.queryId);
  return (q?.paramsSchema || []).map((p) => p.name);
});

const canLink = computed(() => !!props.widget.data?.queryId && paramNames.value.length > 0);

/** 触发源候选：除自己外，所有「可能有点击分类」的组件（图表 + KPI + 表格） */
const sources = computed(() => {
  const clickable = new Set(['kpi', 'table', 'marquee', 'bar', 'hbar', 'line', 'area', 'combo', 'stack', 'pie', 'donut']);
  return props.widgets
    .filter((w) => w.id !== props.widget.id && clickable.has(w.type))
    .map((w) => ({ id: w.id, label: w.style?.title || w.name || w.id }));
});

function labelOf(id: string): string {
  return sources.value.find((s) => s.id === id)?.label || id;
}

onMounted(async () => {
  try {
    queries.value = await biApi.queryOptions();
  } catch {
    queries.value = [];
  }
});

watch(
  () => props.widget.data?.queryId,
  () => { draft.value.param = ''; },
);

function emitLinks(next: WidgetLink[]) {
  emit('patch', { interact: { on: next } });
}

function addLink() {
  const { from, param } = draft.value;
  if (!from || !param) return;
  if (links.value.some((l) => l.from === from && l.param === param)) return;
  emitLinks([...links.value, { from, param }]);
  draft.value = { from: '', param: '' };
}

function removeLink(i: number) {
  const next = links.value.filter((_, k) => k !== i);
  emit('patch', { interact: { on: next } });
}
</script>

<template>
  <div class="bi-interact">
    <el-form label-width="84px" size="small" label-position="left">
      <!-- 已配置的规则 -->
      <el-form-item v-if="links.length" :label="t('bi.props.linkRules')">
        <div class="link-list">
          <div v-for="(l, i) in links" :key="`${l.from}-${l.param}-${i}`" class="link-row">
            <span class="link-text">
              {{ labelOf(l.from) }}
              <span class="arrow">→</span>
              {{ l.param }}
            </span>
            <el-button link :icon="Delete" @click="removeLink(i)" />
          </div>
        </div>
      </el-form-item>

      <!-- 新增 -->
      <template v-if="canLink">
        <el-form-item :label="t('bi.props.linkSource')">
          <el-select v-model="draft.from" :placeholder="t('bi.props.linkPickSource')" style="width: 100%">
            <el-option v-for="s in sources" :key="s.id" :label="s.label" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('bi.props.linkParam')">
          <el-select v-model="draft.param" :placeholder="t('bi.props.linkPickParam')" style="width: 100%">
            <el-option v-for="p in paramNames" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button size="small" :disabled="!draft.from || !draft.param" @click="addLink">
            {{ t('bi.props.linkAdd') }}
          </el-button>
        </el-form-item>
      </template>

      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        :title="t('bi.props.linkNeedQuery')"
      />

      <!-- 运行时生效中的筛选 -->
      <template v-if="active.length">
        <el-form-item :label="t('bi.props.linkActive')">
          <div class="link-list">
            <div v-for="(a, i) in active" :key="i" class="link-row">
              <span class="link-text">
                {{ a.param }} = <b>{{ a.value }}</b>
              </span>
            </div>
            <el-button size="small" @click="emit('clear-filters')">
              {{ t('bi.props.linkClear') }}
            </el-button>
          </div>
        </el-form-item>
      </template>
    </el-form>
  </div>
</template>

<style scoped>
.bi-interact {
  padding-bottom: 4px;
}
.link-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}
.link-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 3px 6px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  font-size: 12px;
  color: var(--color-text-secondary);
  justify-content: space-between;
}
.link-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* AGENTS §7：不写 ALL-CAPS eyebrow，这里只是正文里的一个箭头 */
.arrow {
  color: var(--color-text-muted);
  margin: 0 2px;
}
.bi-interact :deep(.el-alert__title) {
  font-size: 12px;
  line-height: 1.5;
}
</style>