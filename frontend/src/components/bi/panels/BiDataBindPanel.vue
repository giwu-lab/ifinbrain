<script setup lang="ts">
/**
 * 数据绑定面板：选 sys_query → 拉列信息 → 映射 x / series / y / value → 聚合 / 排序 / TopN。
 *
 * 组件一旦绑定就实时预览：panel 自己拉一次数据回传用于「列清单」，
 * 实际渲染由外层 BiCanvas 的 useBiData 统一负责（去重，避免同一 query 打两次）。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { biApi, type BiDataResp, type BiQueryOption, type BiWidget } from '@/api/bi';
import { AGG_LIST, colIndex } from '@/utils/bi/layout';

const props = defineProps<{ widget: BiWidget }>();

const emit = defineEmits<{
  (e: 'patch', patch: Partial<BiWidget>): void;
  (e: 'columns', cols: { key: string; label: string; type: string }[]): void;
}>();

const { t } = useI18n();

const queries = ref<BiQueryOption[]>([]);
const columns = ref<{ key: string; label: string; type: string }[]>([]);
const probing = ref(false);
const probeError = ref('');

const bind = computed(() => props.widget.data);

/** 拉一次数据只为拿列清单（取 1 行足够，走 cacheSec=30 后端还会缓存） */
async function probeColumns(queryId: number) {
  probing.value = true;
  probeError.value = '';
  try {
    const q = queries.value.find((x) => x.id === queryId);
    const defaults: Record<string, string> = {};
    for (const p of q?.paramsSchema || []) {
      if (p.defaultValue) defaults[p.name] = p.defaultValue;
      else if (!p.required) defaults[p.name] = '';
    }
    const resp: BiDataResp = await biApi.queryData({ queryId, params: defaults, cacheSec: 30 });
    columns.value = resp.columns || [];
    emit('columns', columns.value);
  } catch (e: any) {
    columns.value = [];
    emit('columns', []);
    probeError.value = e?.response?.data?.message || e?.message || String(e);
  } finally {
    probing.value = false;
  }
}

onMounted(async () => {
  try {
    queries.value = await biApi.queryOptions();
  } catch {
    queries.value = [];
  }
  if (props.widget.data?.queryId) probeColumns(props.widget.data.queryId);
});

watch(() => props.widget.data?.queryId, (id) => {
  if (id) probeColumns(id);
  else { columns.value = []; emit('columns', []); }
});

// ===================== 变更 =====================

function patchBind(patch: Record<string, any>) {
  emit('patch', { data: { ...(bind.value || { mapping: {} }), ...patch } as any });
}

function patchMapping(patch: Record<string, any>) {
  patchBind({ mapping: { ...(bind.value?.mapping || {}), ...patch } });
}

function patchTransform(patch: Record<string, any>) {
  patchBind({ transform: { ...(bind.value?.transform || {}), ...patch } });
}

function onQueryChange(id: number) {
  columns.value = [];
  emit('columns', []);
  patchBind({ queryId: id, mapping: {}, agg: 'none', transform: {} });
  if (id) probeColumns(id);
}

/** 维度列（可选字符串列） */
const dimCols = computed(() => columns.value.filter((c) => c.type !== 'number'));
const numCols = computed(() => columns.value.filter((c) => c.type === 'number'));

/** 多选指标：直接渲染 checkbox 组 */
const ySelected = computed<string[]>(() => bind.value?.mapping?.y || []);

function toggleY(key: string, on: boolean) {
  const set = new Set(ySelected.value);
  if (on) set.add(key); else set.delete(key);
  patchMapping({ y: [...set] });
}

/** 该组件类型用哪些字段 */
const needsX = computed(() => ['bar', 'hbar', 'line', 'area', 'pie', 'donut', 'funnel', 'treemap', 'kpi', 'table', 'marquee'].includes(props.widget.type));
const needsValue = computed(() => ['pie', 'donut', 'funnel', 'treemap'].includes(props.widget.type));
const needsSeries = computed(() => ['bar', 'hbar', 'line', 'area'].includes(props.widget.type));
const needsTwoY = computed(() => props.widget.type === 'scatter');

const twoY = computed<string[]>(() => bind.value?.mapping?.y || []);
function setTwoY(i: number, key: string) {
  const arr = [...twoY.value];
  arr[i] = key;
  patchMapping({ y: arr });
}
</script>

<template>
  <div class="bi-bind">
    <el-form label-width="84px" size="small" label-position="left">
      <el-form-item :label="t('bi.props.query')">
        <el-select
          :model-value="bind?.queryId"
          :placeholder="t('bi.props.pickQuery')"
          filterable
          style="width: 100%"
          @update:model-value="onQueryChange"
        >
          <el-option
            v-for="q in queries"
            :key="q.id"
            :label="q.name"
            :value="q.id"
          >
            <span>{{ q.name }}</span>
            <span v-if="q.description" class="opt-desc">{{ q.description }}</span>
          </el-option>
        </el-select>
      </el-form-item>

      <div v-if="probeError" class="probe-err">{{ probeError }}</div>

      <template v-if="bind?.queryId">
        <el-form-item :label="t('bi.props.dimension')">
          <el-select
            :model-value="bind?.mapping?.x"
            :placeholder="t('bi.props.notMapped')"
            clearable
            style="width: 100%"
            @update:model-value="(v: any) => patchMapping({ x: v })"
          >
            <el-option v-for="c in dimCols" :key="c.key" :label="c.label || c.key" :value="c.key" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="needsSeries" :label="t('bi.props.series')">
          <el-select
            :model-value="bind?.mapping?.series"
            :placeholder="t('bi.props.notMapped')"
            clearable
            style="width: 100%"
            @update:model-value="(v: any) => patchMapping({ series: v })"
          >
            <el-option v-for="c in dimCols" :key="c.key" :label="c.label || c.key" :value="c.key" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('bi.props.metric')">
          <!-- 散点固定两个指标：X / Y -->
          <div v-if="needsTwoY" class="two-y">
            <el-select
              :model-value="twoY[0]"
              :placeholder="t('bi.props.axisX')"
              style="flex: 1"
              @update:model-value="(v: any) => setTwoY(0, v)"
            >
              <el-option v-for="c in numCols" :key="c.key" :label="c.label || c.key" :value="c.key" />
            </el-select>
            <el-select
              :model-value="twoY[1]"
              :placeholder="t('bi.props.axisY')"
              style="flex: 1"
              @update:model-value="(v: any) => setTwoY(1, v)"
            >
              <el-option v-for="c in numCols" :key="c.key" :label="c.label || c.key" :value="c.key" />
            </el-select>
          </div>
          <!-- 其余：多选 -->
          <el-checkbox-group
            v-else
            :model-value="ySelected"
            class="y-group"
            @update:model-value="(v: any) => patchMapping({ y: v as string[] })"
          >
            <el-checkbox v-for="c in numCols" :key="c.key" :value="c.key" :label="c.key">
              {{ c.label || c.key }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <el-form-item v-if="needsValue && !ySelected.length" :label="t('bi.props.value')">
          <el-select
            :model-value="bind?.mapping?.value"
            :placeholder="t('bi.props.notMapped')"
            clearable
            style="width: 100%"
            @update:model-value="(v: any) => patchMapping({ value: v })"
          >
            <el-option v-for="c in numCols" :key="c.key" :label="c.label || c.key" :value="c.key" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="needsX" :label="t('bi.props.agg')">
          <el-select
            :model-value="bind?.agg || 'none'"
            style="width: 100%"
            @update:model-value="(v: any) => patchBind({ agg: v })"
          >
            <el-option v-for="a in AGG_LIST" :key="a" :label="t(`bi.props.agg_${a}`)" :value="a" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('bi.props.sortField')">
          <el-select
            :model-value="bind?.transform?.sortBy?.field"
            :placeholder="t('bi.props.notMapped')"
            clearable
            style="width: 100%"
            @update:model-value="(v: any) => patchTransform({
              sortBy: v ? { field: v, dir: bind?.transform?.sortBy?.dir || 'desc' } : undefined,
            })"
          >
            <el-option v-for="c in columns" :key="c.key" :label="c.label || c.key" :value="c.key" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('bi.props.sortDir')">
          <el-radio-group
            :model-value="bind?.transform?.sortBy?.dir || 'desc'"
            size="small"
            @update:model-value="(v: any) => patchTransform({
              sortBy: { field: bind?.transform?.sortBy?.field || '', dir: v },
            })"
          >
            <el-radio-button label="desc">{{ t('bi.props.desc') }}</el-radio-button>
            <el-radio-button label="asc">{{ t('bi.props.asc') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item :label="t('bi.props.topN')">
          <el-input-number
            :model-value="bind?.transform?.topN"
            :min="0" :max="100" :step="1"
            controls-position="right"
            style="width: 100%"
            @update:model-value="(v: any) => patchTransform({ topN: v || undefined })"
          />
        </el-form-item>

        <el-form-item :label="t('bi.props.cacheSec')">
          <el-input-number
            :model-value="bind?.cacheSec ?? 30"
            :min="0" :max="300" :step="5"
            controls-position="right"
            style="width: 100%"
            @update:model-value="(v: any) => patchBind({ cacheSec: v ?? 30 })"
          />
        </el-form-item>
      </template>

      <div v-if="probing" class="probe-tip">{{ t('bi.props.probing') }}</div>
    </el-form>
  </div>
</template>

<style scoped>
/* 左右留白由父级 .bi-props 统一给，这里不要再加一套，否则和「基础/样式」两段对不齐 */
.bi-bind {
  padding: 0;
}
.opt-desc {
  float: right;
  color: var(--color-text-muted);
  font-size: 11px;
  margin-left: 10px;
}
.probe-err {
  margin: 0 0 8px;
  padding: 6px 8px;
  font-size: 12px;
  color: var(--color-danger);
  background: var(--color-danger-soft);
  border-radius: 4px;
}
.probe-tip {
  font-size: 12px;
  color: var(--color-text-muted);
}
.y-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 150px;
  overflow: auto;
}
.two-y {
  display: flex;
  gap: 6px;
  width: 100%;
}
</style>