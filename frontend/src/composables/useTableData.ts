/**
 * 表格的查询数据取数与缓存 —— 绑了查询的表格是**展示**组件，数据来自查询而不是填表人。
 *
 * 为什么需要这一层，和 useFieldOptions 是同一个理由但更要紧：
 * 一张表单里放 3 个绑同一查询的表格，设计器画布 + 运行态预览 + 打印视图会同时渲染，
 * 同一个查询就要打 3 次后端。缓存下沉到模块后，无论多少实例总共只打一次。
 *
 * 缓存键 = queryId + params（排序后）。params 里允许写表达式，求值结果不同就是
 * 另一份数据，所以键是在**参数求值之后**算的，参数一变自动切到另一个条目重新取。
 */
import { computed, ref, watch, type ComputedRef, type Ref } from 'vue';
import {
  formDesignApi,
  type FormFieldColumn,
  type FormFieldSchema,
  type FormFieldTableSource,
  type FormTableData,
} from '@/api/formDesign';

interface Entry {
  data: Ref<FormTableData | null>;
  loading: Ref<boolean>;
  error: Ref<string | undefined>;
  promise: Promise<void> | null;
  loaded: boolean;
}

const cache = new Map<string, Entry>();

/** 排序后序列化，避免 {a:1,b:2} 与 {b:2,a:1} 被当成两份数据 */
function signatureOf(src: FormFieldTableSource): string {
  const params = src.params || {};
  const norm = Object.keys(params).sort().map((k) => `${k}=${params[k]}`).join('&');
  return `${src.queryId}|${norm}`;
}

function entryOf(sig: string): Entry {
  let e = cache.get(sig);
  if (!e) {
    e = { data: ref<FormTableData | null>(null), loading: ref(false), error: ref(undefined), promise: null, loaded: false };
    cache.set(sig, e);
  }
  return e;
}

async function load(sig: string, src: FormFieldTableSource): Promise<void> {
  const e = entryOf(sig);
  if (e.loaded) return;
  if (e.promise) return e.promise;          // 并发共享同一个 in-flight
  e.loading.value = true;
  e.promise = (async () => {
    try {
      e.data.value = await formDesignApi.tableData(src);
      e.loaded = true;
      e.error.value = undefined;
    } catch (err: any) {
      // 失败不标记 loaded：允许下一个实例重试，与 useFieldOptions 同一套语义
      e.error.value = err?.response?.data?.message || err?.message || String(err);
      throw err;
    } finally {
      e.loading.value = false;
      e.promise = null;
    }
  })();
  return e.promise;
}

/** 清空缓存 —— 设计器里改了查询/参数后调用，让下次渲染重新取 */
export function invalidateTableData(): void {
  for (const e of cache.values()) {
    e.loaded = false;
    e.data.value = null;
    e.error.value = undefined;
  }
}

export interface TableDataSlot {
  /** 数据行；每行是「列 key → 值」 */
  rows: ComputedRef<Record<string, any>[]>;
  /** 查询侧的列定义 —— 字段自己没配列时用它兜底 */
  queryColumns: ComputedRef<FormFieldColumn[]>;
  loading: ComputedRef<boolean>;
  error: ComputedRef<string | undefined>;
  /** 是否被行数截断 */
  truncated: ComputedRef<boolean>;
  /** 是否绑了查询（= 表格走展示模式） */
  dynamic: ComputedRef<boolean>;
}

/**
 * 取某表格字段绑定的查询数据。
 *
 * 没绑查询 → 全部返回空值，调用方据此回落到「填表人自己填行」的输入模式。
 *
 * @param field        字段 schema（传 ref 则随其变化重算）
 * @param resolveParam 可选：把 tableSource.params 里的 `{{}}` 求值成实际参数值
 */
export function useTableData(
  field: Ref<FormFieldSchema> | FormFieldSchema,
  resolveParam?: (raw: string) => string,
): TableDataSlot {
  const f = () => ('value' in field
    ? (field as Ref<FormFieldSchema>).value
    : field as FormFieldSchema);

  /** 求值后的数据源：params 里的表达式已解析 */
  const src = computed<FormFieldTableSource | null>(() => {
    const raw = f()?.tableSource;
    if (!raw || !raw.queryId) return null;
    if (!resolveParam || !raw.params) return raw;
    const params: Record<string, string> = {};
    for (const [k, v] of Object.entries(raw.params)) {
      params[k] = resolveParam(String(v));
    }
    return { ...raw, params };
  });

  const sig = computed(() => (src.value ? signatureOf(src.value) : null));
  const entry = computed(() => (sig.value ? entryOf(sig.value) : null));

  const dynamic = computed(() => !!src.value);
  const rows = computed(() => entry.value?.data.value?.rows ?? []);
  const queryColumns = computed(() => entry.value?.data.value?.columns ?? []);
  const loading = computed(() => entry.value?.loading.value ?? false);
  const error = computed(() => entry.value?.error.value);
  const truncated = computed(() => entry.value?.data.value?.truncated ?? false);

  // 取数失败不弹 toast：设计器画布上可能有多个表格一起失败，弹一屏红字没人看。
  // 失败状态由调用方渲染成表格上方的提示条，比 toast 更贴近出问题的那个组件。
  watch(sig, (s) => {
    if (!s) return;
    load(s, src.value!).catch(() => {});
  }, { immediate: true });

  return { rows, queryColumns, loading, error, truncated, dynamic };
}