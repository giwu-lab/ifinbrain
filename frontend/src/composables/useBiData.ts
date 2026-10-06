import { ref, shallowRef } from 'vue';
import { biApi, type BiDataResp, type BiWidget } from '@/api/bi';

export interface DataSlot {
  loading: boolean;
  data?: BiDataResp;
  error?: string;
  /** 上次成功取数时刻（毫秒），失败时仍展示旧画面并给出这个时间 */
  lastOkAt?: number;
}

/** 缓存键：queryId + 排序后的 params，保证同一份取数只打一次后端 */
export function signatureOf(w: BiWidget): string | null {
  const b = w.data;
  if (!b || !b.queryId) return null;
  const params = b.params || {};
  const norm = Object.keys(params).sort().map((k) => `${k}=${params[k]}`).join('&');
  return `${b.queryId}|${norm}`;
}

export function cacheSecOf(w: BiWidget): number {
  const v = w.data?.cacheSec;
  if (!Number.isFinite(v) || !v || v <= 0) return 30;
  return Math.min(300, v);
}

/**
 * 大屏取数。多个组件可绑定同一 query + 同一 params —— 这里按 signature 去重，
 * 一个 request 服务多个组件。失败不清空已有 data，播放页据此降级不白屏。
 */
export function useBiData() {
  const slots = shallowRef<Map<string, DataSlot>>(new Map());
  const inFlight = new Map<string, Promise<void>>();

  function slotOf(sig: string): DataSlot | undefined {
    return slots.value.get(sig);
  }

  function put(sig: string, patch: Partial<DataSlot>) {
    const next = new Map(slots.value);
    next.set(sig, { ...(next.get(sig) || { loading: false }), ...patch });
    slots.value = next;
  }

  async function fetchOne(sig: string, widget: BiWidget): Promise<void> {
    if (inFlight.has(sig)) return inFlight.get(sig);
    const task = (async () => {
      put(sig, { loading: true });
      try {
        const resp = await biApi.queryData({
          queryId: widget.data!.queryId,
          params: widget.data!.params || {},
          cacheSec: cacheSecOf(widget),
        });
        put(sig, { loading: false, data: resp, error: undefined, lastOkAt: Date.now() });
      } catch (e: any) {
        const msg = e?.response?.data?.message || e?.message || String(e);
        // 保留 data / lastOkAt —— 播放页继续显示上一帧 + 提示失败
        put(sig, { loading: false, error: msg });
      } finally {
        inFlight.delete(sig);
      }
    })();
    inFlight.set(sig, task);
    return task;
  }

  /** 加载一批组件需要的数据；返回 Promise 便于「刷新完成」提示 */
  async function load(widgets: BiWidget[]): Promise<void> {
    const bySig = new Map<string, BiWidget>();
    for (const w of widgets) {
      if (w.hidden) continue;
      const sig = signatureOf(w);
      if (sig && !bySig.has(sig)) bySig.set(sig, w);
    }
    const tasks: Promise<void>[] = [];
    for (const [sig, w] of bySig) {
      const cur = slotOf(sig);
      // 有缓存且新鲜 → 跳过（force 时不跳）
      if (cur?.data && cur.lastOkAt && Date.now() - cur.lastOkAt < cacheSecOf(w) * 1000) continue;
      tasks.push(fetchOne(sig, w));
    }
    await Promise.allSettled(tasks);
  }

  /** 强制刷新（手动刷新 / 轮播切屏）：忽略新鲜度 */
  async function refresh(widgets: BiWidget[]): Promise<void> {
    const bySig = new Map<string, BiWidget>();
    for (const w of widgets) {
      const sig = signatureOf(w);
      if (sig && !bySig.has(sig)) bySig.set(sig, w);
    }
    await Promise.allSettled([...bySig].map(([sig, w]) => fetchOne(sig, w)));
  }

  /** 某个组件当前的数据（按 signature 命中） */
  function dataFor(w: BiWidget): DataSlot | undefined {
    const sig = signatureOf(w);
    return sig ? slots.value.get(sig) : undefined;
  }

  function clear() {
    slots.value = new Map();
    inFlight.clear();
  }

  return { slots, slotOf, dataFor, load, refresh, clear };
}