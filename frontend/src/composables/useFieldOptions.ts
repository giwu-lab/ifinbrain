import { computed, ref, watch, type ComputedRef, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import {
  formDesignApi,
  type FormFieldOption,
  type FormFieldSchema,
  type FormFieldOptionsSource,
} from '@/api/formDesign';

/**
 * 动态选项（select / radio / checkbox）的取数与缓存。
 *
 * 为什么需要这一层：表格（TABLE）字段是「每行 × 每列」渲染控件的，
 * 10 行 × 1 个下拉列 = 10 个 SelectField 实例，各自取数就是 10 个一样的请求。
 * 缓存下沉到模块后，无论多少实例总共只打一次后端。
 *
 * 缓存键 = queryId + params + labelField + valueField。params 里允许写表达式，
 * 求值结果不同就是不同的选项集 —— 所以键是在**参数求值之后**算的，
 * 参数一变自动切到另一个缓存条目并重新取数（级联下拉就是这么用的）。
 */
interface Entry {
  data: Ref<FormFieldOption[]>;
  loading: Ref<boolean>;
  error: Ref<string | undefined>;
  promise: Promise<void> | null;
  loaded: boolean;
}

const cache = new Map<string, Entry>();

/** 排序后序列化，避免 {a:1,b:2} 与 {b:2,a:1} 被当成两个不同的选项集 */
function signatureOf(src: FormFieldOptionsSource): string {
  const params = src.params || {};
  const norm = Object.keys(params).sort().map((k) => `${k}=${params[k]}`).join('&');
  return `${src.queryId}|${src.labelField}|${src.valueField}|${norm}`;
}

function entryOf(sig: string): Entry {
  let e = cache.get(sig);
  if (!e) {
    e = {
      data: ref<FormFieldOption[]>([]),
      loading: ref(false),
      error: ref<string | undefined>(undefined),
      promise: null,
      loaded: false,
    };
    cache.set(sig, e);
  }
  return e;
}

async function load(sig: string, src: FormFieldOptionsSource): Promise<void> {
  const e = entryOf(sig);
  if (e.loaded) return;
  if (e.promise) return e.promise;          // 并发共享同一个 in-flight

  e.loading.value = true;
  e.promise = (async () => {
    try {
      e.data.value = (await formDesignApi.options(src)) || [];
      e.loaded = true;
      e.error.value = undefined;
    } catch (err: any) {
      // 失败不标记 loaded：允许下一个实例重试，跟 useOptionsCache 同一套语义
      const msg = err?.response?.data?.message || err?.message || String(err);
      e.error.value = msg;
      throw err;
    } finally {
      e.loading.value = false;
      e.promise = null;
    }
  })();
  return e.promise;
}

/** 清空所有缓存 —— 设计器里改了数据源后调用 */
export function invalidateFieldOptions() {
  for (const e of cache.values()) {
    e.loaded = false;
    e.data.value = [];
    e.error.value = undefined;
  }
}

export interface FieldOptionsSlot {
  /** 最终选项：有动态源就用动态结果，否则用 schema 里的静态 options */
  options: ComputedRef<FormFieldOption[]>;
  loading: ComputedRef<boolean>;
  error: ComputedRef<string | undefined>;
  /** 是否走了动态取数（用于渲染 loading 态） */
  dynamic: ComputedRef<boolean>;
}

/**
 * 取某字段的选项列表。
 *
 * 配了 optionsSource → 取数填充；没配 → 直接给静态 options，不触发任何请求。
 *
 * @param field  字段 schema（传 ref 则随其变化重算）
 * @param resolveParam 可选：把 optionsSource.params 里的 `{{}}` 求值成实际参数值。
 *                     不传则按字面量用（设计期固定参数的场景）。
 */
export function useFieldOptions(
  field: Ref<FormFieldSchema> | FormFieldSchema,
  resolveParam?: (raw: string) => string,
): FieldOptionsSlot {
  const { t } = useI18n();

  const f = () => ('value' in field
    ? (field as Ref<FormFieldSchema>).value
    : field as FormFieldSchema);

  /** 求值后的选项源：params 里的表达式已解析 */
  const src = computed<FormFieldOptionsSource | null>(() => {
    const raw = f().optionsSource;
    if (!raw) return null;
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

  /** 没配动态源就走 schema 静态 options —— 零开销，且天然响应式 */
  const options = computed<FormFieldOption[]>(() =>
    entry.value ? entry.value.data.value : (f().options ?? []),
  );
  const loading = computed(() => entry.value?.loading.value ?? false);
  const error = computed(() => entry.value?.error.value);

  /** 配置或参数一变就换缓存条目并取数 —— 级联下拉靠这条链路 */
  watch(sig, (s) => {
    if (!s) return;
    const e = entryOf(s);
    if (e.loaded) return;
    load(s, src.value!).catch(() => {
      ElMessage.warning(t('formDesigner.warning.optionsLoadFailed'));
    });
  }, { immediate: true });

  return { options, loading, error, dynamic };
}