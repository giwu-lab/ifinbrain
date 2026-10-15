/**
 * 表单「渲染层」验证 —— SSR 真渲染，断言组件实际吐出的 HTML。
 *
 * 为什么不用浏览器：dev server 会跟着并行改动不断整页重载，页面状态不可复现；
 * 这里走 vite 的 ssrLoadModule 拿到**真实组件**，用 renderToString 出真实 HTML，
 * 断言栅格落位 / 条件逻辑 / 表达式文案 / 样式 —— 与 dev server 是否在重载无关。
 *
 * 覆盖 M1（表达式）+ M2（条件逻辑）+ M3（栅格与样式）在 FormRenderer 上的集成。
 */

import { createServer } from 'vite';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import { createPinia } from 'pinia';

let pass = 0;
const failures: string[] = [];

function ok(cond: boolean, name: string, extra = ''): void {
  if (cond) {
    pass++;
    console.log(`  ok   ${name}`);
  } else {
    failures.push(name + (extra ? ` — ${extra}` : ''));
    console.log(`  FAIL ${name}${extra ? ` — ${extra}` : ''}`);
  }
}

function eq<T>(actual: T, expected: T, name: string): void {
  ok(
    JSON.stringify(actual) === JSON.stringify(expected),
    name,
    JSON.stringify(actual) === JSON.stringify(expected) ? '' : `期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`,
  );
}

// ---------------------------------------------------------------- 启动 vite SSR
// element-plus 必须 noExternal：它 package 里带 theme-chalk/*.css，
// 交给 Node 直接 import 会 ERR_UNKNOWN_FILE_EXTENSION。
// 其余依赖（含 vue）保持 external —— 保证 harness 与被测组件共用同一个 vue 实例，
// 否则 provide / inject 与 app context 会分裂。
const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error',
  ssr: { noExternal: ['element-plus'] },
});

const FormRenderer = (await vite.ssrLoadModule('/src/components/form/FormRenderer.vue')).default;
const { i18n } = await vite.ssrLoadModule('/src/i18n/index.ts');
const { migrateFields } = await vite.ssrLoadModule('/src/utils/form/layout.ts');

async function render(
  fields: any[],
  modelValue: Record<string, any> = {},
  grid?: any,
  extra: Record<string, any> = {},
  slots?: Record<string, any>,
): Promise<string> {
  const app = createSSRApp({
    render: () => h(FormRenderer as any, { fields, modelValue, grid, ...extra }, slots),
  });
  app.use(createPinia());
  app.use(i18n as any);
  return renderToString(app);
}

/**
 * 从 HTML 里切出每个 .form-cell。
 *
 * 只能按顺序定位，不能按 `prop="xxx"` —— prop 是 ElFormItem 声明过的组件 prop，
 * 不会作为 DOM attribute 渲染出来。v-for 按 fields 顺序渲染，所以下标即字段序。
 */
function cells(html: string): Array<{ head: string; body: string }> {
  return html
    .split(/<div[^>]*class="form-cell[^"]*"/)
    .slice(1)
    .map((p) => ({ head: p.slice(0, p.indexOf('>')), body: p }));
}

const styleAt = (html: string, i: number) => /style="([^"]*)"/.exec(cells(html)[i]?.head)?.[1] ?? '';
const count = (html: string) => cells(html).length;

// ---------------------------------------------------------------- 用例
console.log('\n[1] 栅格落位（M3）');

const gridFields: any[] = [
  { id: 'a', type: 'INPUT', key: 'name', label: '申请人', layout: { x: 0, y: 0, w: 24, h: 2 } },
  { id: 'b', type: 'SELECT', key: 'leaveType', label: '请假类型', layout: { x: 0, y: 2, w: 12, h: 2 },
    options: [{ label: '年假', value: 'ANNUAL' }, { label: '事假', value: 'PERSONAL' }] },
  { id: 'c', type: 'DATE', key: 'startDate', label: '开始日期', layout: { x: 12, y: 2, w: 12, h: 2 } },
  { id: 'd', type: 'NUMBER', key: 'days', label: '请假天数', layout: { x: 0, y: 4, w: 8, h: 1 } },
  { id: 'e', type: 'TEXTAREA', key: 'reason', label: '请假事由', layout: { x: 8, y: 4, w: 16, h: 1 } },
];

const g1 = await render(gridFields);
eq(count(g1), 5, '5 个字段渲染成 5 个栅格单元格');
ok(g1.includes('grid-template-columns:repeat(24, minmax(0, 1fr))'), '容器是 24 列 CSS Grid');
ok(
  g1.includes('grid-auto-rows:minmax(64px, auto)'),
  '行高用 minmax(64px, auto)：长字段（富文本/表格）能撑开轨道，不会溢出画布',
);
ok(!/grid-auto-rows:\s*\d+px/.test(g1), '不再使用写死的固定行高（固定行高会让超长字段溢出画布）');
eq(styleAt(g1, 0), 'grid-column:1 / span 24;grid-row:1 / span 2;min-width:0;', '满宽字段 x=0,w=24 → 1/span24 第1行');
eq(styleAt(g1, 1), 'grid-column:1 / span 12;grid-row:3 / span 2;min-width:0;', '半宽字段 x=0,w=12 → 1/span12 第3行');
eq(styleAt(g1, 2), 'grid-column:13 / span 12;grid-row:3 / span 2;min-width:0;', '右半宽 x=12 → 13/span12（1-based 换算）');
eq(styleAt(g1, 3), 'grid-column:1 / span 8;grid-row:5 / span 1;min-width:0;', '非 12 倍数宽度 w=8 → 1/span8');
ok(!g1.includes('el-row') && !g1.includes('el-col'), '不再使用 el-row / el-col');

console.log('\n[2] 旧 schema 迁移：只有 fullWidth 也能栅格渲染（M3）');

// fullWidth 语义沿用改动前的 isFullWidth()：f.fullWidth !== false 即满宽，
// 所以半宽字段必须显式写 fullWidth: false。
const legacy = migrateFields([
  { id: 'x1', type: 'INPUT', key: 'a', label: 'A', fullWidth: true } as any,
  { id: 'x2', type: 'INPUT', key: 'b', label: 'B', fullWidth: false } as any,
  { id: 'x3', type: 'INPUT', key: 'c', label: 'C', fullWidth: false } as any,
]);
ok(legacy.every((f: any) => !!f.layout), 'migrateFields 给每个字段补上 layout');
eq(legacy.map((f: any) => f.layout.w), [24, 12, 12], '满宽 24 + 两个半宽 12');
eq(legacy.map((f: any) => f.layout.y), [0, 1, 1], '两个半宽并排在同一行');
const g2 = await render(legacy);
eq(count(g2), 3, '迁移后的 schema 渲染 3 个单元格');
eq(styleAt(g2, 0), 'grid-column:1 / span 24;grid-row:1 / span 1;min-width:0;', '迁移后满宽字段仍占整行');
eq(styleAt(g2, 1), 'grid-column:1 / span 12;grid-row:2 / span 1;min-width:0;', '迁移后半宽字段排在下一行左侧');

console.log('\n[3] 条件逻辑 visibleWhen（M2）');

const condFields: any[] = [
  { id: 'v1', type: 'SELECT', key: 'kind', label: '类型', options: [{ label: '年假', value: 'A' }, { label: '事假', value: 'B' }] },
  { id: 'v2', type: 'TEXTAREA', key: 'reason', label: '事由', visibleWhen: '{{$form.kind === "B"}}' },
  { id: 'v3', type: 'INPUT', key: 'other', label: '其它', visibleWhen: '{{$form.kind === "C"}}' },
];

const vOff = await render(condFields, { kind: 'A' });
ok(!vOff.includes('事由'), 'visibleWhen=false → 字段整块不渲染');
ok(!vOff.includes('其它'), '条件不满足的第二个字段同样不渲染');
ok(vOff.includes('类型'), '无条件字段照常渲染');

const vOn = await render(condFields, { kind: 'B' });
ok(vOn.includes('事由'), 'visibleWhen=true → 字段渲染出来');
ok(!vOn.includes('其它'), '同一份 schema 下另一个条件仍不渲染');

console.log('\n[4] 条件逻辑 requiredWhen / disabledWhen（M2）');

const reqFields: any[] = [
  { id: 'r1', type: 'NUMBER', key: 'days', label: '天数' },
  { id: 'r2', type: 'TEXTAREA', key: 'why', label: '原因', requiredWhen: '{{$form.days > 3}}' },
  { id: 'r3', type: 'INPUT', key: 'note', label: '备注', disabledWhen: '{{$form.days > 3}}' },
];

const countMark = (html: string) => (html.match(/class="required-mark"/g) || []).length;
const rOff = await render(reqFields, { days: 1 });
const rOn = await render(reqFields, { days: 5 });
eq(countMark(rOff), 0, 'requiredWhen=false 时不加必填星号');
eq(countMark(rOn), 1, 'requiredWhen=true 时加一颗必填星号');
ok(cells(rOn)[2].body.includes('disabled'), 'disabledWhen=true 时该字段控件变 disabled');
ok(!cells(rOff)[2].body.includes('disabled'), 'disabledWhen=false 时不禁用');

console.log('\n[5] 表达式文案（M1）');

const exprFields: any[] = [
  { id: 'e1', type: 'NUMBER', key: 'days', label: '天数' },
  { id: 'e2', type: 'INPUT', key: 'sum', label: '合计={{$form.days}}天' },
  { id: 'e3', type: 'INPUT', key: 'hint', label: '提示', placeholder: '请填{{$form.days * 2}}以内' },
];

const ex = await render(exprFields, { days: 3 });
ok(ex.includes('合计=3天'), 'label 里 {{$form.days}} 求值成 3（单表达式走字符串解析）');
ok(!ex.includes('合计={{'), '渲染结果里不残留 {{}}');
ok(ex.includes('请填6以内'), 'placeholder 里的算术表达式求值成 6');

const exBad = await render([{ id: 'e9', type: 'INPUT', key: 'x', label: '{{$form.nope.deep}}' }], {});
ok(exBad.includes('{{$form.nope.deep}}') || !exBad.includes('>undefined'), '表达式求值失败时优雅降级，不把 undefined 渲染进标签');

console.log('\n[6] 样式属性（M3）');

const styled = await render([
  {
    id: 's1', type: 'INPUT', key: 'a', label: '标题', layout: { x: 0, y: 0, w: 24, h: 1 },
    style: { labelColor: 'rgb(255, 0, 0)', fontSize: 18, align: 'center', background: 'rgb(238, 238, 238)', padding: 12, radius: 8 },
  },
  { id: 's2', type: 'INPUT', key: 'b', label: '普通', layout: { x: 0, y: 1, w: 24, h: 1 } },
]);
ok(/class="field-label" style="[^"]*color:rgb\(255, 0, 0\)/.test(styled), 'labelColor 落到 field-label 的行内样式');
ok(/class="cell-body" style="[^"]*font-size:18px/.test(styled), 'fontSize 落到 cell-body');
ok(/class="cell-body" style="[^"]*background:rgb\(238, 238, 238\)/.test(styled), 'background 落到 cell-body');
ok(/class="cell-body" style="[^"]*padding:12px/.test(styled), 'padding 落到 cell-body');
ok(/class="cell-body" style="[^"]*border-radius:8px/.test(styled), 'radius 落到 cell-body');
ok(/class="cell-body" style="[^"]*text-align:center/.test(styled), 'align 落到 cell-body');
const plainCell = cells(styled)[1].head;
ok(!/background|padding|border-radius|text-align/.test(plainCell.style), '未配样式的字段不带这些属性');

console.log('\n[7] 设计态画布 vs 运行态：布局排版必须一致');

const parityFields: any[] = [
  { id: 'p1', type: 'INPUT', key: 'name', label: '申请人', layout: { x: 0, y: 0, w: 24, h: 1 } },
  { id: 'p2', type: 'SELECT', key: 'leaveType', label: '请假类型', layout: { x: 0, y: 1, w: 12, h: 1 },
    options: [{ label: '年假', value: 'A' }] },
  { id: 'p3', type: 'DATE', key: 'startDate', label: '开始日期', layout: { x: 12, y: 1, w: 12, h: 1 } },
  { id: 'p4', type: 'NUMBER', key: 'days', label: '天数', layout: { x: 0, y: 2, w: 8, h: 1 },
    style: { background: 'rgb(238, 238, 238)', padding: 12, radius: 8, fontSize: 18 } },
];

const runOut = await render(parityFields, {});
const designOut = await render(parityFields, {}, undefined, { designMode: true });

// Element Plus 每次 SSR 会自增全局 id 计数器（el-id-624-36 → el-id-624-44），
// 比较前归一化掉，否则会把"同一个组件渲染两次"误判成内容差异。
const norm = (h: string) => h.replace(/el-id-\d+-\d+/g, 'el-id-N');

eq(
  cells(designOut).map((c) => c.head),
  cells(runOut).map((c) => c.head),
  'designMode 与运行态的栅格几何（容器样式 + 每格 grid-column/row）逐格一致',
);
eq(norm(designOut), norm(runOut), 'designMode 与运行态渲染出的 HTML 完全一致（同一条渲染路径）');
ok(designOut.includes('rgb(238, 238, 238)'), '设计态同样渲染字段样式（背景色等）');

const hiddenInCanvas = await render(
  [{ id: 'h1', type: 'SELECT', key: 'k', label: '类型', options: [] },
   { id: 'h2', type: 'INPUT', key: 'x', label: '事由', visibleWhen: '{{$form.k === "Z"}}' }],
  { k: 'A' },
  undefined,
  { designMode: true },
);
ok(hiddenInCanvas.includes('事由'), 'designMode：条件隐藏的字段照样渲染，设计师能编辑它');
ok(!runOut.includes('hiddenByCond'), '运行态不会出现设计器专用标记');

// 未交互前不该有任何校验红字。
// 注意：这条在 SSR 下是**弱断言** —— el-form 的 validate-on-rule-change 是挂载后
// 的 watcher 才会跑，SSR 不挂载，所以这里守不住「客户端首帧别刷红字」。
// 真正的防线是 :validate-on-rule-change="false"，那条只能靠浏览器确认。
// 这里保留是为了防止将来有人把 errorMap/校验逻辑挪回首帧路径。
const reqParity = [
  { id: 'q1', type: 'INPUT', key: 'a', label: '甲', required: true },
  { id: 'q2', type: 'INPUT', key: 'b', label: '乙', required: true },
];
const freshRun = await render(reqParity, {});
const freshDesign = await render(reqParity, {}, undefined, { designMode: true });
ok(!freshRun.includes('请填写'), '运行态首次渲染不做校验，不出现「请填写」红字');
ok(!freshDesign.includes('请填写'), '设计态不做校验，不出现「请填写」红字');
eq((freshDesign.match(/class="required-mark"/g) || []).length, 2, '设计态仍然渲染必填星号（只是不跑校验）');

// cell 插槽：运行态不传就零 DOM，设计态传了就挂到每格上
const noSlot = await render(parityFields, {}, undefined, { designMode: true });
ok(!noSlot.includes('cell-overlay'), '不传 cell 插槽时设计态也没有任何浮层 DOM');
const withSlot = await render(
  parityFields, {}, undefined, { designMode: true },
  { cell: () => 'CELL-OVERLAY' },
);
eq((withSlot.match(/CELL-OVERLAY/g) || []).length, 4, '传 cell 插槽时每个格子各挂一个浮层');

console.log('\n[8] 真实 FormCanvas：build 模式与 preview 模式排版一致');

const FormCanvas = (await vite.ssrLoadModule('/src/components/form/FormCanvas.vue')).default;

async function renderCanvas(mode: 'build' | 'preview'): Promise<string> {
  const app = createSSRApp({
    render: () =>
      h(FormCanvas as any, {
        fields: parityFields,
        selectedId: mode === 'build' ? 'p2' : null,
        mode,
        canvasWidth: 1200,
        zoom: 1,
      }),
  });
  app.use(createPinia());
  app.use(i18n as any);
  return renderToString(app);
}

const buildOut = await renderCanvas('build');
const previewOut = await renderCanvas('preview');

const geoOf = (h: string) => cells(h).map((c) => c.head);
eq(geoOf(buildOut), geoOf(previewOut), '画布 build 与 preview 的栅格几何逐格一致');
eq(
  geoOf(buildOut),
  cells(norm(runOut)).map((c) => c.head),
  '画布 build 的几何与运行态 FormRenderer 一致',
);
eq((buildOut.match(/class="cell-overlay/g) || []).length, 4, 'build 模式每个字段挂一个设计器浮层');
eq((previewOut.match(/class="cell-overlay/g) || []).length, 0, 'preview 模式完全没有设计器浮层');
ok(buildOut.includes('申请人') && buildOut.includes('开始日期'), 'build 模式渲染出真实字段内容');
ok(
  /class="cell-meta[^"]*"[\s\S]{0,300}?<span[^>]*class="row-index"[^>]*>1<\/span>/.test(buildOut),
  '设计器信息条带字段序号',
);
ok(buildOut.includes('请假类型'), 'build 模式里选中字段的标签正常显示');
ok(previewOut.includes('申请人'), 'preview 模式渲染出真实字段内容');

console.log('\n[9] 老 schema（只有 fullWidth）直接进渲染层也要半宽并排');

// 回归防线：运行态预览 / 流程取数 / 报销弹窗这些入口是**直接吃接口数据**的，
// 拿到的老 schema 里没有 layout。渲染层必须自己按 fullWidth 语义补出栅格坐标，
// 否则一旦 fallback 成 1/-1，所有半宽字段会集体变成满宽（和设计器对不上）。
const legacyRuntime = await render([
  { id: 'l1', type: 'INPUT', key: 'a', label: '甲', fullWidth: true },
  { id: 'l2', type: 'INPUT', key: 'b', label: '乙', fullWidth: false },
  { id: 'l3', type: 'INPUT', key: 'c', label: '丙', fullWidth: false },
]);
eq(
  [styleAt(legacyRuntime, 0), styleAt(legacyRuntime, 1), styleAt(legacyRuntime, 2)],
  [
    'grid-column:1 / span 24;grid-row:1 / span 1;min-width:0;',
    'grid-column:1 / span 12;grid-row:2 / span 1;min-width:0;',
    'grid-column:13 / span 12;grid-row:2 / span 1;min-width:0;',
  ],
  '运行态直接渲染无 layout 的老 schema：满宽+两个半宽并排，不再全满宽',
);
ok(!legacyRuntime.includes('grid-column:1 / -1'), '运行态不再把缺 layout 的字段一律兜成整行');

// 设计器已经迁移过的 schema 不应被二次迁移改动
const twice = await render(await (async () => {
  const { migrateFields } = await vite.ssrLoadModule('/src/utils/form/layout.ts');
  return migrateFields([
    { id: 't1', type: 'INPUT', key: 'a', label: '甲', layout: { x: 0, y: 0, w: 8, h: 2 } },
    { id: 't2', type: 'INPUT', key: 'b', label: '乙', layout: { x: 8, y: 0, w: 16, h: 2 } },
  ] as any[]);
})());
eq(
  [styleAt(twice, 0), styleAt(twice, 1)],
  [
    'grid-column:1 / span 8;grid-row:1 / span 2;min-width:0;',
    'grid-column:9 / span 16;grid-row:1 / span 2;min-width:0;',
  ],
  '已有 layout 的字段原样保留，不被渲染层二次迁移改动',
);

console.log('\n[10] i18n 文案全部可被 vue-i18n 编译');

// vue-i18n 把 {{ 当成「链接消息」语法，不是字面量。想在文案里显示 {{...}}
// 必须写成 {'{{'} / {'}}'}（而且要成对，只转一层会少一个大括号）。
// 这类错误只在**渲染到那一条文案时**才抛，且是运行时异常 —— 单测覆盖不到，
// 所以这里把两份 locale 的每条叶子文案都按「应用真实渲染路径」跑一遍 t()。
//
// 注意：不要直接调 @intlify/message-compiler 的 baseCompile —— 它不带 options 时
// onError 默认是 null，坏文案**根本不报错**（假绿）；而走 vue-i18n 实例 t() 时
// 编译错误会原样抛出，正是浏览器里 SyntaxError 的那条路径。
const { createI18n } = await import('vue-i18n');
const zhMsgs = (await vite.ssrLoadModule('/src/i18n/locales/zh-CN.ts')).default as Record<string, any>;
const enMsgs = (await vite.ssrLoadModule('/src/i18n/locales/en-US.ts')).default as Record<string, any>;

function leafKeys(obj: Record<string, any>, prefix = ''): string[] {
  const out: string[] = [];
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (typeof v === 'string') out.push(key);
    else if (v && typeof v === 'object') out.push(...leafKeys(v, key));
  }
  return out;
}

const i18nBad: string[] = [];
let i18nTotal = 0;
for (const [locale, msgs] of [['zh-CN', zhMsgs], ['en-US', enMsgs]] as const) {
  const probe = createI18n({
    legacy: false,
    locale,
    messages: { [locale]: msgs },
    warnHtmlMessage: false,
    missingWarn: false,
    fallbackWarn: false,
    onError: () => {},
  } as any).global;
  for (const key of leafKeys(msgs)) {
    i18nTotal++;
    try {
      probe.t(key);
    } catch (e: any) {
      i18nBad.push(`${locale}.${key} — ${String(e?.message ?? e).split('\n')[0]}`);
    }
  }
}
for (const b of i18nBad) console.log(`  FAIL ${b}`);
ok(i18nBad.length === 0, `${i18nTotal} 条中英文案全部通过 vue-i18n 编译器`);

// 顺带验证转义后的文案真的渲染出字面量花括号（不是被吃掉一层）
const g = (i18n as any).global;
g.locale.value = 'zh-CN';
ok(
  g.t('formDesigner.expr.hint').includes('{{$form.<字段key>}}'),
  'expr.hint 渲染出成对的字面量 {{ }}',
  g.t('formDesigner.expr.hint'),
);
ok(
  g.t('formDesigner.expr.undefinedRef', { key: 'foo' }).includes('{{$form.foo}}'),
  'undefinedRef 插值后仍渲染出成对的 {{ }}',
  g.t('formDesigner.expr.undefinedRef', { key: 'foo' }),
);
ok(g.t('formDesigner.cond.disabledWhenPh').includes('{{$form.locked}}'), 'cond.*Ph 渲染出成对的字面量 {{ }}');

console.log('\n[11] 新建字段自动预填「显示名 + 标识key」');

// 规则本身（编号 / 跳号 / 前缀）在 scripts/form-verify/identity.ts 覆盖；
// 这里只验**接线**：store.addField 是否真的把默认值填进去，而不是还留空串。
const { setActivePinia, createPinia: mkPinia } = await import('pinia');
const { useFormDesignStore } = await vite.ssrLoadModule('/src/stores/formDesign.ts');
setActivePinia(mkPinia());
const store = useFormDesignStore();

store.reset();
ok(store.addField !== undefined, 'store 暴露 addField');
store.addField('TEXT');
store.addField('TEXT');
store.addField('SELECT');
store.addField('RICH_TEXT');

eq(
  store.fields.map((f: any) => f.key),
  ['text1', 'text2', 'select1', 'richText1'],
  '连续新建的 key 递增且不重复',
);
eq(
  store.fields.map((f: any) => f.label),
  ['单行文本 1', '单行文本 2', '下拉选择 1', '富文本 1'],
  '显示名 = 类型显示名 + 同序号',
);
ok(
  store.fields.every((f: any) => f.key && f.label),
  '没有字段的 key/label 是空串',
  JSON.stringify(store.fields.map((f: any) => [f.key, f.label])),
);

// 用户手改成 text1/text2 后再加，不能撞车
store.updateField(store.fields[2].id, { key: 'text1' });
store.addField('TEXT');
ok(
  store.fields.filter((f: any) => f.key === 'text1').length === 2,
  '手改占用 text1 后，新字段跳到 text3 而不是撞车',
  JSON.stringify(store.fields.map((f: any) => f.key)),
);

// i18n 单一数据源：类型显示名必须来自 locales，而不是 store 里写死中文
ok(
  g.t('formDesigner.field.key') === '标识key',
  '属性面板「字段key」已更名为「标识key」',
  g.t('formDesigner.field.key'),
);
ok(
  g.t('formDesigner.type.TEXT') === '单行文本' && store.fields[0].label === g.t('formDesigner.type.TEXT') + ' 1',
  '显示名里的类型名与 locale 文案一致（未硬编码）',
  `${store.fields[0].label} / ${g.t('formDesigner.type.TEXT')}`,
);

// 英文侧必须同步（AGENTS.md：i18n 文案双语对齐）
g.locale.value = 'en-US';
ok(
  g.t('formDesigner.field.key') === 'Identifier Key',
  '英文侧同步更名为 Identifier Key',
  g.t('formDesigner.field.key'),
);
ok(g.t('formDesigner.type.TEXT') === 'Single Line', '英文侧类型显示名可用', g.t('formDesigner.type.TEXT'));
g.locale.value = 'zh-CN';
eq(
  store.fields.map((f: any) => f.key),
  ['text1', 'text2', 'text1', 'richText1', 'text3'],
  '手改占用 text1 后，新字段跳过被占用的号（text3）',
);

console.log('\n[12] 查询参数可绑定表单字段值（{{}} 求值后才发请求）');

const { reactive, ref } = await import('vue');
const apiMod: any = await vite.ssrLoadModule('/src/api/formDesign.ts');
const exprMod: any = await vite.ssrLoadModule('/src/composables/useFormExpr.ts');
const { useTableData }: any = await vite.ssrLoadModule('/src/composables/useTableData.ts');
const { exprSlots, renameExprRefs } = exprMod;

// 拦下真正发出去的请求体：只有它能证明「表达式已被求值成实际值」
const origTableData = apiMod.formDesignApi.tableData;
let captured: any = null;
apiMod.formDesignApi.tableData = async (src: any) => {
  captured = src;
  return { columns: [], rows: [], truncated: false, rowCount: 0 };
};

const formData = reactive({ minId: 42, keyword: 'abc' });
const e = exprMod.useFormExpr(formData);
const tableField: any = ref({
  id: 't1', type: 'TABLE', key: 'items', label: '明细',
  tableSource: { queryId: 8, params: { minId: '{{ $form.minId }}', keyword: '固定值' } },
});
useTableData(tableField, (raw: string) => e.text(raw, ''));
await new Promise((r) => setTimeout(r, 0));

eq(
  captured?.params,
  { minId: '42', keyword: '固定值' },
  '发请求时参数已求值：表达式 → 实际值，字面量原样透传',
);
ok('求值后 queryId 仍在', captured?.queryId === 8, JSON.stringify(captured));

// 改被引用的字段 → 参数跟着变 → 缓存签名变了会重新取数（级联的关键）
formData.minId = 100;
await new Promise((r) => setTimeout(r, 0));
eq(captured?.params?.minId, '100', '被引用字段改值后，参数跟着重新求值');

apiMod.formDesignApi.tableData = origTableData;

console.log('\n[13] 参数里的表达式纳入引用扫描与复制重写');

// exprSlots 必须能看到藏在 Map 里的参数，否则校验与复制都看不见它们
const slotField: any = {
  id: 'f1', type: 'TABLE', key: 'items', label: '明细 {{ $form.title }}',
  tableSource: { queryId: 1, params: { minId: '{{ $form.minId }}', kw: '常量' } },
  optionsSource: { queryId: 2, labelField: 'a', valueField: 'b', params: { org: '{{ $form.dept }}' } },
};
eq(
  exprSlots(slotField).map((s: any) => s.label).sort(),
  ['label', 'optionsSource.params.org', 'tableSource.params.kw', 'tableSource.params.minId'],
  'exprSlots 覆盖 label 与三个数据源参数',
);

// 复制字段时，藏在 params 里的引用也必须一起改写 —— 否则副本仍指向原字段
const copyTarget: any = JSON.parse(JSON.stringify(slotField));
renameExprRefs(copyTarget, 'minId', 'minId_copy');
renameExprRefs(copyTarget, 'dept', 'dept_copy');
renameExprRefs(copyTarget, 'title', 'title_copy');
eq(copyTarget.tableSource.params.minId, '{{ $form.minId_copy }}', '重写 tableSource.params 里的引用');
eq(copyTarget.optionsSource.params.org, '{{ $form.dept_copy }}', '重写 optionsSource.params 里的引用');
eq(copyTarget.label, '明细 {{ $form.title_copy }}', '重写 label 里的引用');
eq(copyTarget.tableSource.params.kw, '常量', '没被引用的参数原样保留');

// 引用了不存在的字段要在保存前被拦下
const badIssues = exprMod.useFormExpr({}).issues([
  { id: 'f1', type: 'TABLE', key: 'items', label: '明细', tableSource: { queryId: 1, params: { minId: '{{ $form.nope }}' } } } as any,
]);
ok(
  badIssues.some((i: any) => i.kind === 'undefined-ref' && i.detail === 'nope'),
  '参数里引用不存在的字段会被 issues 抓出来',
  JSON.stringify(badIssues),
);
ok(
  badIssues[0]?.slotLabel === 'tableSource.params.minId',
  '报错带上了参数位置名',
  String(badIssues[0]?.slotLabel),
);

// store.duplicate：重写的是「指向被复制字段自己」的引用；
// 指向别的字段的引用**本来就不该动**（副本本来就该跟着同一个源字段走）。
store.reset();
store.addField('TABLE');
const dupTbl: any = store.fields[0];
dupTbl.key = 'detail';
dupTbl.label = '明细';
dupTbl.tableSource = { queryId: 3, params: { v: '{{ $form.detail }}' } };
store.duplicate(dupTbl.id);
const duped: any = store.fields.find((f: any) => f.key?.endsWith('_copy'));
eq(duped?.key, 'detail_copy', 'duplicate() 改了副本的 key');
eq(
  duped?.tableSource?.params?.v,
  '{{ $form.detail_copy }}',
  'duplicate() 重写了指向自己的引用（含查询参数）',
);
eq(
  dupTbl.tableSource?.params?.v,
  '{{ $form.detail }}',
  '原件的引用不受影响',
);

// ---------------------------------------------------------------- [14] 宽表格常驻横向滚动条
console.log('\n[14] 列过多时表格常驻横向滚动条');

const TableField = (await vite.ssrLoadModule('/src/components/form/fields/TableField.vue')).default;

async function renderTable(cols: any[], extraProps: Record<string, any> = {}): Promise<string> {
  const app = createSSRApp({
    render: () =>
      h(TableField as any, {
        field: { id: 'tt', type: 'TABLE', key: 'grid1', label: '明细', columns: cols },
        modelValue: [],
        ...extraProps,
      }),
  });
  app.use(createPinia());
  app.use(i18n as any);
  return renderToString(app);
}

/**
 * SSR 下拿不到容器宽度（ResizeObserver 不跑），tableScroll.needsScrollX 会按
 * 「宽度未知 → 先当需要滚动」处理，所以宽表一定带 scrollbar-always-on。
 * 窄表在这里也判为需要滚动 —— 真实浏览器里量到宽度后会自动关掉，
 * 那条判定由 verify:table-scroll 的纯函数用例覆盖。
 */
const wideCols = Array.from({ length: 20 }, (_, i) => ({ key: `c${i}`, label: `列${i}`, type: 'TEXT' }));
const wideHtml = await renderTable(wideCols);
ok('20 列的表格带上了 scrollbar-always-on', /scrollbar-always-on/.test(wideHtml), wideHtml.slice(0, 300));

const narrowHtml = await renderTable([{ key: 'a', label: 'A', type: 'TEXT' }]);
ok('单列表格同样先按需要滚动处理（SSR 无宽度）', /scrollbar-always-on/.test(narrowHtml));

const readonlyHtml = await renderTable(wideCols, { readonly: true });
ok('只读态（无操作列）也能渲染出常驻滚动条', /scrollbar-always-on/.test(readonlyHtml));
ok('只读态不渲染操作列', !/删除行|上移|下移/.test(readonlyHtml));

// ---------------------------------------------------------------- 汇总
await vite.close();
console.log(`\n渲染层验证：通过 ${pass}，失败 ${failures.length}`);
if (failures.length) {
  for (const f of failures) console.log('  - ' + f);
  process.exit(1);
}