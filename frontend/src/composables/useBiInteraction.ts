import { computed, onBeforeUnmount, shallowRef, type Ref } from 'vue';
import type { BiWidget } from '@/api/bi';
import { onChartClick } from '@/utils/bi/events';

/**
 * 组件联动的运行时状态。
 *
 * ## 关键设计：联动值**不进 layout**
 *
 * 点击产生的筛选值只存在本 composable 的 overrides 里，绝不写回
 * `store.widgets[i].data.params`。原因：那样一点，大屏保存时会把用户
 * 「上次随手点的那个分类」固化进布局，下次打开就莫名其妙只剩那一类数据。
 * 布局里存的只有 `widget.interact`（规则），值永远是运行时状态。
 *
 * ## 为什么不用改 useBiData
 *
 * useBiData 按 `signatureOf()` = queryId + params 去重。联动把参数一改，
 * 签名自然变 → `dataFor()` 自然命中另一个 slot → `load()` 自然重新取数。
 * 所以本 composable 只负责产出「params 被覆盖后的 widget 副本」，
 * 下游照常收 widget 数组，useBiData 一行都不用动。
 *
 * 这也复用了 View.vue 里 `widgetsWithUrlParams()` 早就有的同一套派生模式。
 */
export function useBiInteraction(base: Ref<BiWidget[]> | (() => BiWidget[])) {
  const get = typeof base === 'function' ? base : () => base.value;

  /** widgetId → { 参数名: 值 } */
  const overrides = shallowRef<Map<string, Record<string, string>>>(new Map());

  /** 供渲染 / 取数使用：已合并联动值的 widget 副本 */
  const effective = computed<BiWidget[]>(() => {
    const ov = overrides.value;
    if (!ov.size) return get();
    return get().map((w) => {
      const o = ov.get(w.id);
      // 没有覆盖就返回**原对象**（保持引用），避免整棵树白白重建一遍、
      // 让所有 ECharts 的 deep watch 无谓触发
      if (!o || !Object.keys(o).length) return w;
      return {
        ...w,
        data: { ...(w.data as any), params: { ...(w.data?.params || {}), ...o } },
      } as BiWidget;
    });
  });

  /**
   * overrides 的指纹。只 watch 它而不 watch effective / base，
   * 是为了不在「改了组件属性」「拖动布局」这类无关变化上也触发一次 reload。
   */
  const overrideSig = computed(() => {
    const parts: string[] = [];
    overrides.value.forEach((v, k) => {
      parts.push(`${k}:${Object.keys(v).sort().map((p) => `${p}=${v[p]}`).join(',')}`);
    });
    return parts.sort().join('|');
  });

  /** 当前生效的筛选，给 UI 显示「正在按 X 过滤」用 */
  const activeFilters = computed(() => {
    const out: { widgetId: string; widgetName: string; param: string; value: string }[] = [];
    const map = overrides.value;
    if (!map.size) return out;
    for (const w of get()) {
      const o = map.get(w.id);
      if (!o) continue;
      for (const [param, value] of Object.entries(o)) {
        out.push({ widgetId: w.id, widgetName: w.style?.title || w.name || w.id, param, value });
      }
    }
    return out;
  });

  function clearAll() {
    if (overrides.value.size) overrides.value = new Map();
  }

  function clearWidget(widgetId: string) {
    if (!overrides.value.has(widgetId)) return;
    const next = new Map(overrides.value);
    next.delete(widgetId);
    overrides.value = next;
  }

  /** 点到源组件 → 把值写进所有订阅了它的组件的参数 */
  function applyClick(from: string, value: string) {
    let next: Map<string, Record<string, string>> | null = null;
    for (const w of get()) {
      const links = w.interact?.on || [];
      if (!links.length) continue;
      // 自己点自己没意义，还容易写出「用自己的值过滤自己」的环
      if (w.id === from) continue;
      for (const l of links) {
        if (l.from !== from || !l.param) continue;
        const cur = (next || overrides.value).get(w.id) || {};
        if (cur[l.param] === value) continue; // 值没变就不重建 Map
        const merged = { ...cur, [l.param]: value };
        if (!next) next = new Map(overrides.value);
        next.set(w.id, merged);
      }
    }
    if (next) overrides.value = next;
  }

  const off = onChartClick((e) => applyClick(e.widgetId, e.value));

  onBeforeUnmount(() => off());

  return { overrides, effective, overrideSig, activeFilters, applyClick, clearAll, clearWidget };
}