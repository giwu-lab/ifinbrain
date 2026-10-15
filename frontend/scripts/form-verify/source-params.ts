/**
 * 查询参数绑定表单字段 —— 设计期参数拆分规则验证。
 *
 * 覆盖：字面量原样发 / 绑定字段的参数被摘掉 / skipped 顺序与内容 /
 *       undefined 与 null 兜底 / 空对象与 undefined 输入 / 脏数据不影响字面量。
 *
 * 背景见 utils/form/sourceParams.ts 的注释：设计期没有表单数据，表达式求不出值，
 * 原文发给后端会被当字面量做类型校验（实测 number 型参数报「无法解析为 number」）。
 */
import { designTimeParams } from '../../src/utils/form/sourceParams';

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

console.log('\n[1] 字面量参数原样发出');
{
  const r = designTimeParams({ minId: '1', maxId: '100', keyword: '张三' });
  eq('全部进 send', r.send, { minId: '1', maxId: '100', keyword: '张三' });
  eq('skipped 为空', r.skipped, []);
}
{
  // 空串是合法字面量（表示「清空该条件」），不能当成表达式摘掉
  const r = designTimeParams({ keyword: '' });
  eq('空串仍是字面量', r.send, { keyword: '' });
  eq('空串不算 skipped', r.skipped, []);
}

console.log('\n[2] 绑定了字段的参数被摘掉');
{
  const r = designTimeParams({ minId: '{{ $form.deptId }}', maxId: '9' });
  eq('只留字面量', r.send, { maxId: '9' });
  eq('skipped 记下名字', r.skipped, ['minId']);
}
{
  const r = designTimeParams({ a: '{{ $form.x }}', b: '{{$form.y}}', c: '1' });
  eq('没有空格的写法也算表达式', r.skipped, ['a', 'b']);
  eq('字面量不受牵连', r.send, { c: '1' });
}
{
  // 表达式嵌在文本中间 —— hasExpression 认的是「含 {{…}}」，不是「整串就是表达式」
  const r = designTimeParams({ like: 'prefix-{{ $form.x }}-suffix' });
  eq('表达式嵌在文本里也摘掉', r.skipped, ['like']);
  eq('嵌表达式的串不进 send', r.send, {});
}
{
  const r = designTimeParams({ a: '{{ $form.x }}', b: '{{ $form.y }}' });
  eq('多个全绑时 send 为空对象', r.send, {});
  eq('skipped 保持配置顺序', r.skipped, ['a', 'b']);
}

console.log('\n[3] 输入兜底');
{
  eq('undefined → 空 send', designTimeParams(undefined), { send: {}, skipped: [] });
  eq('null → 空 send', designTimeParams(null), { send: {}, skipped: [] });
  eq('空对象 → 空 send', designTimeParams({}), { send: {}, skipped: [] });
}
{
  // 参数值理论上都是 string，但 schema 是从库里读出来的 JSON，脏数据不该把整个同步打断
  const r = designTimeParams({ a: undefined, b: null, c: '1' } as unknown as Record<string, string>);
  eq('undefined/null 的参数被忽略', r.send, { c: '1' });
  eq('忽略项不算 skipped', r.skipped, []);
}
{
  const r = designTimeParams({ n: 12 } as unknown as Record<string, string>);
  ok('数字型脏数据不进 skipped（没有 {{}}）', r.skipped.length === 0 && r.send.n === 12);
}

console.log('\n[4] 不改动入参');
{
  const input = { minId: '{{ $form.a }}', maxId: '5' };
  const snapshot = JSON.stringify(input);
  designTimeParams(input);
  eq('原对象未被修改', JSON.stringify(input), snapshot);
}
{
  // send 必须是新对象：调用方会把它塞进请求体，不能反过来改到 schema 上
  const input = { maxId: '5' };
  const r = designTimeParams(input);
  ok('send 不是入参本身', r.send !== input);
}

console.log(`\n查询参数绑定验证：通过 ${pass}，失败 ${failures.length}`);
if (failures.length) {
  for (const f of failures) console.log('  - ' + f);
  process.exit(1);
}
