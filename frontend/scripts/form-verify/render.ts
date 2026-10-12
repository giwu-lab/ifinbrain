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

// ---------------------------------------------------------------- 汇总
await vite.close();
console.log(`\n渲染层验证：通过 ${pass}，失败 ${failures.length}`);
if (failures.length) {
  for (const f of failures) console.log('  - ' + f);
  process.exit(1);
}