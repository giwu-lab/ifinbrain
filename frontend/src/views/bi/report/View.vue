<script setup lang="ts">
/**
 * 报表播放页：查询头 + 虚拟滚动表格。
 *
 * 加载模型是**增量**的：首屏 pageSize 行，滚到底再拉 pageSize 行。
 * 不做「一次性拉全量」—— 那正是要避免的爆内存写法。
 *
 * 换查询头参数 = 重新查询：清空 rows、把 offset 归零、按新参数取第一页。
 * 追加参数（不清空）只在「追加」场景用，本页不提供 —— 报表语义是「查一批数据」，
 * 拼接两批不同参数的结果没有意义。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { reportApi, type Report, type ReportColumn, type ReportParamSchema } from '@/api/report';
import { firstMissingRequired, initialParams, mergeColumns, visibleParams } from '@/utils/report/header';
import QueryHeader from '@/components/report/QueryHeader.vue';
import VirtualTable from '@/components/report/VirtualTable.vue';
import { toTableColumns } from '@/components/report/types';

const { t } = useI18n();
const route = useRoute();

const reportId = computed(() => Number(route.params.id));
const report = ref<Report | null>(null);
/** 该报表绑定的查询的参数 schema —— 运行时从 query 读，报表不冗余存 */
const paramSchema = ref<ReportParamSchema[]>([]);

const values = ref<Record<string, string>>({});
const rows = ref<any[][]>([]);
const resultColumns = ref<ReportColumn[]>([]);
const hasMore = ref(false);
const capped = ref(false);
const loading = ref(false);
const loadingMore = ref(false);
const error = ref('');

const collapsed = ref(false);

const pageSize = computed(() => report.value?.pageSize ?? 50);
const maxRows = computed(() => report.value?.maxRows ?? 50000);

/** 有效列 = 结果列 ∩ 报表列配置 */
const tableColumns = computed(() => toTableColumns(mergeColumns(resultColumns.value, report.value?.columns)));

const shownParams = computed(() =>
  visibleParams(paramSchema.value, report.value?.header)
);

async function loadReport() {
  loading.value = true;
  error.value = '';
  try {
    const r = await reportApi.detail(reportId.value);
    report.value = r;
    values.value = initialParams(paramSchema.value, r.defaultParams);
    // 参数 schema 存在绑定的查询上，不在报表里 —— 走 query-options 拿
    const opts = await reportApi.queryOptions();
    const opt = opts.find((o) => o.id === r.queryId);
    paramSchema.value = opt?.params ?? [];
    values.value = initialParams(paramSchema.value, r.defaultParams);
    collapsed.value = r.header?.collapsed ?? false;
    await query();
  } catch (e: any) {
    error.value = e?.message || t('report.loadFailed');
  } finally {
    loading.value = false;
  }
}

/** 首屏查询：清空重来 */
async function query() {
  const miss = firstMissingRequired(shownParams.value, values.value);
  if (miss) {
    error.value = t('report.missingParam', {
      name: shownParams.value.find((p) => p.name === miss)?.label || miss,
    });
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const resp = await reportApi.run(reportId.value, {
      params: values.value,
      offset: 0,
      size: pageSize.value,
    });
    resultColumns.value = resp.columns ?? [];
    rows.value = resp.rows ?? [];
    hasMore.value = resp.hasMore;
    capped.value = resp.capped;
  } catch (e: any) {
    error.value = e?.message || t('report.queryFailed');
  } finally {
    loading.value = false;
  }
}

/** 增量加载：offset = 已加载行数 */
async function loadMore() {
  if (loadingMore.value || !hasMore.value) return;
  loadingMore.value = true;
  try {
    const resp = await reportApi.run(reportId.value, {
      params: values.value,
      offset: rows.value.length,
      size: pageSize.value,
    });
    if (resp.rows?.length) {
      rows.value = rows.value.concat(resp.rows);
      hasMore.value = resp.hasMore;
    } else {
      // 收到空页就认定取尽。即便后端仍报 hasMore（比如并发裁剪挖出的空洞），
      // 继续拉会得到同样的空页，虚拟表格的「填不满可视区就补页」会无限重试。
      hasMore.value = false;
    }
    capped.value = resp.capped;
    // 列只在首屏返回，后续页为省带宽是空数组。
    // 必须「非空才覆盖」—— 写成 `if (!len) columns = resp.columns` 会把已有列清成空数组，
    // 表格随即一行都渲染不出来（表现为「一直在加载但没有数据」）。
    if (resp.columns?.length) {
      resultColumns.value = resp.columns;
    }
  } catch (e: any) {
    error.value = e?.message || t('report.queryFailed');
    // 出错就停止继续拉，否则滚动会不断重试同一页
    hasMore.value = false;
  } finally {
    loadingMore.value = false;
  }
}

/** 有界缓存超限 → 从头裁剪，DOM 与内存同步下降 */
function onTrim(keptRows: any[][]) {
  rows.value = keptRows;
}

function onReset() {
  values.value = initialParams(paramSchema.value, report.value?.defaultParams);
  query();
}

function onToggleCollapse() {
  collapsed.value = !collapsed.value;
}

onMounted(loadReport);

watch(reportId, loadReport);
</script>

<template>
  <div class="rpv-page">
    <div class="rpv-top">
      <div class="rpv-title-wrap">
        <h1 class="rpv-title">{{ report?.name || t('report.title') }}</h1>
        <p v-if="report?.description" class="rpv-desc muted">{{ report.description }}</p>
      </div>
      <div class="rpv-meta tabular">
        <span v-if="report">{{ t('report.loaded', { n: rows.length }) }}</span>
        <span v-if="capped" class="rpv-cap">{{ t('report.capped', { n: maxRows }) }}</span>
      </div>
    </div>

    <QueryHeader
      v-if="shownParams.length"
      v-model="values"
      :params="shownParams"
      :columns="report?.header?.columns ?? 4"
      :collapsed="collapsed"
      :loading="loading"
      @search="query"
      @reset="onReset"
      @toggle-collapse="onToggleCollapse"
    />

    <div v-if="error" class="rpv-error">
      <span>{{ error }}</span>
      <el-button link type="primary" :icon="Refresh" @click="query">{{ t('report.retry') }}</el-button>
    </div>

    <div class="rpv-body">
      <VirtualTable
        :rows="rows"
        :columns="tableColumns"
        :has-more="hasMore"
        :loading-more="loadingMore"
        :load-more="loadMore"
        :on-trim="onTrim"
      />
    </div>
  </div>
</template>

<style scoped>
.rpv-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  min-height: 0;
  padding: 16px;
  background: var(--color-bg);
}

.rpv-top {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.rpv-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--color-text);
}

.rpv-desc {
  margin: 4px 0 0;
  font-size: var(--font-size-sm);
}

.rpv-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}

.rpv-cap {
  color: var(--color-warning);
}

.rpv-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--color-danger);
  border-radius: 6px;
  background: var(--color-danger-soft);
  color: var(--color-danger);
  font-size: var(--font-size-sm);
}

.rpv-body {
  flex: 1 1 0;
  min-height: 0;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  overflow: hidden;
}
</style>
