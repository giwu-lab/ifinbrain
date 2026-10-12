/**
 * 大屏组件间联动的事件总线。
 *
 * 为什么不用 mitt：这里只有一个事件（图表点击），而引入依赖要多一条
 * package.json + 一次版本升级风险。一个 Set 就够，且天然类型安全。
 *
 * 为什么用「全局总线」而不是 props/emit 层层往下传：
 * ChartBase → BiWidgetBody → BiWidgetFrame → BiCanvas → 页面，跨了四层
 * 组件、两个使用方（设计器 + 播放页）。走总线后 ChartBase 只管发，
 * 页面只管订，中间三层完全不用知道这件事存在 —— 加联动不需要改外壳组件。
 *
 * 借鉴 mfish 的 mitt 总线思路，但它把 6 个通用事件全开；我们只开真正用得上的
 * 「点击分类」，先把一个场景做透，避免一上来就设计一套没人用的通用事件 DSL。
 */

export interface ChartClickEvent {
  /** 被点击的组件 id —— 订阅方据此判断「是不是我的触发源」 */
  widgetId: string;
  /** 被点中的分类值（柱子 / 扇区的名称），联动默认取它 */
  value: string;
  seriesName: string;
  dataIndex: number;
}

export type ChartClickHandler = (e: ChartClickEvent) => void;

const handlers = new Set<ChartClickHandler>();

/** 订阅；返回退订函数（组件 unmount 时务必调用，否则闭包持有整棵组件树） */
export function onChartClick(h: ChartClickHandler): () => void {
  handlers.add(h);
  return () => { handlers.delete(h); };
}

/**
 * 一个订阅方抛错不能连累其它订阅方，也不该把异常抛进 ECharts 的事件循环
 * （那里抛错只会变成一条难定位的 console 栈）。
 */
export function emitChartClick(e: ChartClickEvent): void {
  for (const h of Array.from(handlers)) {
    try {
      h(e);
    } catch (err) {
      console.error('[bi-interaction] subscriber failed', err);
    }
  }
}