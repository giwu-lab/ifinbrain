import { computed } from 'vue';
import { defineStore } from 'pinia';
import { dictApi, type DictItem, type DictType } from '@/api/dict';

const STORAGE_KEY = 'ifinbrain.dict-cache.v1';
const TTL_MS = 5 * 60 * 1000; // 5 minutes

interface CacheEnvelope {
  savedAt: number;
  items: Record<string, DictItem[]>;
}

function loadFromStorage(): CacheEnvelope | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const env = JSON.parse(raw) as CacheEnvelope;
    if (Date.now() - env.savedAt > TTL_MS) return null;
    return env;
  } catch {
    return null;
  }
}

function saveToStorage(items: Record<string, DictItem[]>) {
  try {
    const env: CacheEnvelope = { savedAt: Date.now(), items };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(env));
  } catch {
    /* ignore quota errors */
  }
}

function clearStorage() {
  try { localStorage.removeItem(STORAGE_KEY); } catch { /* ignore */ }
}

interface DictState {
  items: Record<string, DictItem[]>;
  loading: boolean;
  loadedAt: number | null;
}

export const useDictStore = defineStore('dict', {
  state: (): DictState => ({
    items: {},
    loading: false,
    loadedAt: null,
  }),
  actions: {
    /** 加载全量字典缓存：优先 localStorage，过期则远程拉取 */
    async load(force = false) {
      if (this.loading) return;
      this.loading = true;
      try {
        if (!force) {
          const env = loadFromStorage();
          if (env) {
            this.items = env.items;
            this.loadedAt = env.savedAt;
            return;
          }
        }
        const data = await dictApi.cache();
        this.items = data;
        this.loadedAt = Date.now();
        saveToStorage(data);
      } finally {
        this.loading = false;
      }
    },

    /** 按 typeCode 取条目列表（同步） */
    get(code: string): DictItem[] {
      return this.items[code] ?? [];
    },

    /** 按 (typeCode, code) 查单条 */
    getByCode(typeCode: string, code: string): DictItem | undefined {
      return this.get(typeCode).find((it) => it.code === code);
    },

    /** 按 (typeCode, value) 查单条——value 字段用于对接业务侧原始字符串（如 OCR 返回值） */
    getByValue(typeCode: string, value: string): DictItem | undefined {
      return this.get(typeCode).find((it) => it.value === value);
    },

    /** 强制刷新（CRUD 后调用） */
    async refresh() {
      clearStorage();
      await this.load(true);
    },

    /** 字典变更后清掉本地缓存，下次访问时重新拉 */
    invalidate() {
      clearStorage();
      this.items = {};
      this.loadedAt = null;
    },
  },
});

/** 便捷 hook：组件中使用 */
export function useDict() {
  const store = useDictStore();
  return {
    store,
    load: store.load.bind(store),
    refresh: store.refresh.bind(store),
    get: store.get.bind(store),
    getByCode: store.getByCode.bind(store),
    getByValue: store.getByValue.bind(store),
    invalidate: store.invalidate.bind(store),
  };
}

/** 领域 hook：发票状态 */
export const DICT_INVOICE_STATUS = 'invoice_status';
export function useInvoiceStatus() {
  const { store, get, getByValue } = useDict();
  return {
    items: computed(() => get(DICT_INVOICE_STATUS)),
    successItem: computed(() => getByValue(DICT_INVOICE_STATUS, 'success')),
    errorItem: computed(() => get(DICT_INVOICE_STATUS).find((i) => i.value === 'error')),
    /** OCR message 是否表示「成功」 */
    isSuccess: (msg?: string) => getByValue(DICT_INVOICE_STATUS, 'success')?.value === msg,
    /** OCR message 是否表示「识别失败」 */
    isError: (msg?: string) => {
      const items = get(DICT_INVOICE_STATUS);
      const errorItem = items.find((i) => i.value === 'error');
      // 提取成局部量是为了让类型收口：DictItem.value 声明为可选（value?: string），
      // TS 无法穿过 find 的谓词推出「命中的那一项 value 必然等于 'error'」，
      // 于是后面的 startsWith 收到的是 string | undefined。
      // 运行时其实不存在 undefined（能匹配上就说明 value === 'error'），
      // 这里只是把这条不变量写进代码，让类型检查和阅读者看到的是同一件事。
      const token = errorItem?.value;
      if (!token || !msg) return false;
      return msg === token || msg.startsWith(token);
    },
  };
}

/** 领域 hook：发票费用类别 */
export const DICT_INVOICE_EXPENSE_CATEGORY = 'invoice_expense_category';
export function useInvoiceExpenseCategory() {
  const { get } = useDict();
  const items = computed(() => get(DICT_INVOICE_EXPENSE_CATEGORY));
  return {
    items,
    /**
     * el-select 的 options。
     * value 故意取字典 value（= 中文文案，与 fapiao_cn 的 expense_category_label
     * 逐字一致），而不是小写英文 code —— 上传页 v-model 绑的就是这一列，
     * 解析返回「餐饮」必须能原样命中选项回显。value 为空的兜底到 label。
     * get() 不按 status 过滤（store 层是全量快照），停用项在这里滤掉。
     */
    options: computed(() =>
      items.value
        .filter((i) => i.status === 1)
        .map((i) => ({ label: i.label, value: i.value || i.label }))),
  };
}

/** 领域 hook：文件格式 */
export const DICT_FILE_FORMAT = 'file_format';
export function useFileFormat() {
  const { store, get } = useDict();
  const codes = computed(() => get(DICT_FILE_FORMAT).map((i) => i.code));
  return {
    items: computed(() => get(DICT_FILE_FORMAT)),
    codes,
    /** 用于 el-upload accept 属性：'.pdf,.xml,.ofd' */
    acceptAttr: computed(() =>
      '.' + codes.value.filter(Boolean).join(',.')),
  };
}