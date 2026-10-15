/**
 * 表格「绑定查询 → 自动匹配字段」的选择性同步规则验证。
 *
 * 覆盖：查询列类型推断 / 自动匹配生成列 / 增删保留三类变更 / 人工调整不被冲掉 /
 *       顺序以查询为准 / 脏数据兜底 / 是否需要同步的判定。
 */
import {
  columnFromQuery,
  fieldTypeOfQueryColumn,
  mergeTableColumns,
  tableColumnsInSync,
} from '../../src/utils/form/tableColumns';
import type { FormFieldColumn, QueryColumn } from '../../src/api/formDesign';

let pass = 0;
const failures: string[] = [];

function eq(name: string, a: unknown, e: unknown) {
  if (JSON.stringify(a) === JSON.stringify(e)) {
    pass++;
    console.log(`  ok   ${name}`);
  } else {
    failures.push(name);
    console.log(`  FAIL ${name}\n         期望 ${JSON.stringify(e)}\n         实际 ${JSON.stringify(a)}`);
  }
}
function ok(name: string, c: boolean, x = '') {
  if (c) { pass++; console.log(`  ok   ${name}`); }
  else { failures.push(name); console.log(`  FAIL ${name} ${x}`); }
}

const q = (key: string, label?: string, type?: string, width?: number | null): QueryColumn =>
  ({ key, label, type, width } as QueryColumn);

console.log('\n[1] 查询列类型 → 单元格控件类型');
eq('number → NUMBER', fieldTypeOfQueryColumn('number'), 'NUMBER');
eq('date → DATE', fieldTypeOfQueryColumn('date'), 'DATE');
eq('datetime → DATETIME', fieldTypeOfQueryColumn('datetime'), 'DATETIME');
eq('string → TEXT', fieldTypeOfQueryColumn('string'), 'TEXT');
// 布尔落 TEXT：做成单选需要 options，而「是/否」得走 i18n，纯函数里造不出来
eq('boolean → TEXT（不做单选，避免硬编码文案）', fieldTypeOfQueryColumn('boolean'), 'TEXT');
eq('unknown → TEXT', fieldTypeOfQueryColumn('unknown'), 'TEXT');
eq('没给 type → TEXT', fieldTypeOfQueryColumn(undefined), 'TEXT');
eq('大小写不敏感（PG 大写列名）', fieldTypeOfQueryColumn('NUMBER'), 'NUMBER');

console.log('\n[2] 自动匹配：查询列 → 表格列');
{
  const c = columnFromQuery(q('amount', '金额', 'number', 120));
  eq('key 透传', c.key, 'amount');
  eq('标题取查询友好名', c.label, '金额');
  eq('类型按查询列推断', c.type, 'NUMBER');
  eq('宽度透传', c.width, 120);
}
{
  const c = columnFromQuery(q('raw_col'));
  eq('查询没给友好名时用列名兜底', c.label, 'raw_col');
  eq('width 为 null → undefined（不是 0）', c.width, undefined);
}

console.log('\n[3] 首次绑定：现有列为空，全部按查询生成');
{
  const r = mergeTableColumns(undefined, [q('a', '甲'), q('b', '乙', 'number'), q('c', '丙', 'date')]);
  eq('三列都在', r.columns.map((c) => c.key), ['a', 'b', 'c']);
  eq('全部算新增', r.added, ['a', 'b', 'c']);
  eq('没有删除', r.removed, []);
  eq('没有保留', r.kept, []);
  eq('类型已按查询推断', r.columns.map((c) => c.type), ['TEXT', 'NUMBER', 'DATE']);
}

console.log('\n[4] 查询新增一列：只补新的，其余不动');
{
  const existing: FormFieldColumn[] = [
    { key: 'a', label: '甲（我改过的标题）', type: 'TEXT' },
    { key: 'b', label: '乙', type: 'NUMBER' },
  ];
  const r = mergeTableColumns(existing, [q('a'), q('b', '乙', 'number'), q('c', '丙')]);
  eq('列数 +1', r.columns.length, 3);
  eq('新增的是 c', r.added, ['c']);
  eq('a/b 算保留', r.kept, ['a', 'b']);
  eq('没有删除', r.removed, []);
  eq(
    '我改过的标题不会被查询的列名覆盖',
    r.columns[0].label,
    '甲（我改过的标题）',
  );
}

console.log('\n[5] 查询删掉一列：跟着删，不留死列');
{
  const existing: FormFieldColumn[] = [
    { key: 'a', label: '甲', type: 'TEXT' },
    { key: 'gone', label: '过期列', type: 'TEXT' },
  ];
  const r = mergeTableColumns(existing, [q('a'), q('b', '新列')]);
  eq('删除 gone', r.removed, ['gone']);
  eq('保留 a', r.kept, ['a']);
  eq('新增 b', r.added, ['b']);
  eq('结果只剩两列', r.columns.map((c) => c.key), ['a', 'b']);
}

console.log('\n[6] 幸存列的人工调整必须原样保留（这是「选择性」的核心）');
{
  const options = [{ label: '甲选项', value: 'a' }];
  const existing: FormFieldColumn[] = [
    {
      key: 'kind', label: '类别（人话）', type: 'SELECT',
      width: 200, required: true, options,
    },
  ];
  const r = mergeTableColumns(existing, [q('kind', 'kind（机器名）', 'string')]);
  const c = r.columns[0];
  eq('标题保留人写的', c.label, '类别（人话）');
  eq('单元格类型保留人工选的 SELECT', c.type, 'SELECT');
  eq('宽度保留', c.width, 200);
  eq('必填保留', c.required, true);
  eq('列选项保留', c.options, options);
  eq('key 不变', c.key, 'kind');
}

console.log('\n[7] 顺序以查询为准');
{
  const existing: FormFieldColumn[] = [
    { key: 'a', label: '甲', type: 'TEXT' },
    { key: 'b', label: '乙', type: 'TEXT' },
  ];
  const r = mergeTableColumns(existing, [q('b'), q('a')]);
  eq('按查询给出的顺序', r.columns.map((c) => c.key), ['b', 'a']);
}

console.log('\n[8] 脏数据兜底：重复列名 / 空列名 / 空 key 的历史列');
{
  const incoming = [q('a'), q('a'), q('  '), q('b')];
  const r = mergeTableColumns(undefined, incoming);
  eq('重复与空列名被滤掉', r.columns.map((c) => c.key), ['a', 'b']);

  const r2 = mergeTableColumns([{ key: '', label: '坏列', type: 'TEXT' }], [q('a')]);
  eq('历史数据里的空 key 列不会卡住同步', r2.columns.map((c) => c.key), ['a']);
}

console.log('\n[9] 是否需要同步：key 对得上就别动，避免打开设计器就把表单判成「未保存」');
{
  const cols = mergeTableColumns(undefined, [q('a', '甲'), q('b', '乙', 'number')]).columns;
  ok('列 key 与查询一致 → 无需同步', tableColumnsInSync(cols, [q('a', '甲'), q('b', '乙', 'number')]));
  ok('查询少了一列 → 需要同步', !tableColumnsInSync(cols, [q('a', '甲')]));
  ok('查询多了一列 → 需要同步', !tableColumnsInSync(cols, [q('a', '甲'), q('b', '乙', 'number'), q('c')]));
  ok('查询把列改了名（key 变）→ 需要同步', !tableColumnsInSync(cols, [q('a'), q('b2')]));
  ok('都没配列且查询也没列 → 一致', tableColumnsInSync(undefined, []));
}
{
  // 标题/类型归用户管：把 string 列改成下拉是有意的，不该被判成「没对上」。
  // 否则 merge 原样保留 → 判定还是没对上 → 每次打开设计器空转一次。
  const cols: FormFieldColumn[] = [
    { key: 'a', label: '甲（我改的标题）', type: 'SELECT', options: [{ label: 'x', value: 'x' }] },
  ];
  ok('用户改了标题与单元格类型 → 仍算对得上（不空转）', tableColumnsInSync(cols, [q('a', '甲', 'string')]));
  ok('查询返回重复/空列名不影响判定', tableColumnsInSync(cols, [q('a', '甲'), q('a'), q('  ')]));
}

console.log('\n[10] 同步不会就地改到入参（避免 UI 上出现幽灵变更）');
{
  const existing: FormFieldColumn[] = [{ key: 'a', label: '甲', type: 'TEXT', width: 100 }];
  const snapshot = JSON.stringify(existing);
  mergeTableColumns(existing, [q('a'), q('b', '新')]);
  eq('原数组未被修改', JSON.stringify(existing), snapshot);
}

console.log(`\n表格列同步验证：通过 ${pass}，失败 ${failures.length}`);
if (failures.length) {
  for (const f of failures) console.log('  - ' + f);
  process.exit(1);
}