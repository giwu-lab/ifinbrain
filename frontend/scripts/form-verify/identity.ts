/**
 * 新建字段「显示名 + 标识 key」预填规则的行为验证。
 *
 * 覆盖：默认命名 / 同类型连续编号 / 跳号避开已占用 / 跨类型各自编号 /
 *       非英文类型枚举名（RICH_TEXT → richText）/ 大小写与空格归一。
 */
import { KEY_PREFIX, defaultIdentity } from '../../src/utils/form/identity';
import type { FieldType } from '../../src/api/formDesign';

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

const ALL_TYPES: FieldType[] = [
  'TEXT', 'TEXTAREA', 'NUMBER', 'SELECT', 'RADIO', 'CHECKBOX',
  'DATE', 'DATETIME', 'USER', 'DEPT', 'FILE', 'RICH_TEXT', 'TABLE', 'DIVIDER',
];

console.log('\n[1] 第一个字段：key 与显示名都有默认值（不再是空串）');
{
  const r = defaultIdentity('TEXT', [], '单行文本');
  eq('标识 key 默认 text1', r.key, 'text1');
  eq('显示名默认「单行文本 1」', r.label, '单行文本 1');
  ok('key 不是空串', r.key.length > 0);
  ok('label 不是空串', r.label.length > 0);
}

console.log('\n[2] 同类型连续新建：序号递增');
{
  const taken: string[] = [];
  const seq = [1, 2, 3].map(() => {
    const r = defaultIdentity('SELECT', taken, '下拉选择');
    taken.push(r.key);
    return r;
  });
  eq('三个下拉的 key', seq.map((r) => r.key), ['select1', 'select2', 'select3']);
  eq('三个下拉的显示名', seq.map((r) => r.label), ['下拉选择 1', '下拉选择 2', '下拉选择 3']);
}

console.log('\n[3] 已占用的序号会被跳过，不产生重复 key');
{
  const r = defaultIdentity('TEXT', ['text1', 'text2', 'text4'], '单行文本');
  eq('跳过已占用的 1/2/4，落到 text3', r.key, 'text3');
}
{
  // 用户手改成 text1/text2 后再加字段，不应撞车
  let taken = ['text1', 'text2'];
  const r = defaultIdentity('TEXT', taken, '单行文本');
  taken.push(r.key);
  const r2 = defaultIdentity('TEXT', taken, '单行文本');
  ok('连加两个都不与已有 key 冲突', r.key !== r2.key, `${r.key} / ${r2.key}`);
  eq('第二个是 text4（text3 已占用）', r2.key, 'text4');
}

console.log('\n[4] 跨类型各自独立编号');
{
  eq('第一个数字字段', defaultIdentity('NUMBER', [], '数字').key, 'number1');
  eq('第一个日期字段不受数字影响', defaultIdentity('DATE', ['number1'], '日期').key, 'date1');
  eq('第二个数字字段', defaultIdentity('NUMBER', ['number1', 'date1'], '数字').key, 'number2');
}

console.log('\n[5] key 用 lowerCamelCase，不是类型枚举名（表达式里好写）');
eq('RICH_TEXT → richText', KEY_PREFIX.RICH_TEXT, 'richText');
eq('RICH_TEXT 的第一个 key', defaultIdentity('RICH_TEXT', [], '富文本').key, 'richText1');
for (const t of ALL_TYPES) {
  ok(`${t} 的 key 前缀不含下划线`, !KEY_PREFIX[t].includes('_'), KEY_PREFIX[t]);
  ok(`${t} 的 key 前缀首字母小写`, /^[a-z]/.test(KEY_PREFIX[t]), KEY_PREFIX[t]);
}

console.log('\n[6] key 必须是合法标识符（能安全进 {{$form.xxx}} 与数据键）');
for (const t of ALL_TYPES) {
  const k = defaultIdentity(t, [], 'T').key;
  ok(`${t} → ${k} 匹配标识符正则`, /^[a-z][A-Za-z0-9]*$/.test(k), k);
}

console.log('\n[7] 脏数据不影响预填：空串 / 空白 key 不算占用');
{
  eq('空串 key 不占用序号', defaultIdentity('TEXT', ['', '   ', 'text1'], '单行文本').key, 'text2');
}

console.log('\n[8] 14 种字段类型都有前缀（漏一个就会退化成 fieldN）');
{
  const missing = ALL_TYPES.filter((t) => !KEY_PREFIX[t]);
  eq('缺前缀的类型', missing, []);
  eq('前缀数量与类型数量一致', Object.keys(KEY_PREFIX).length, ALL_TYPES.length);
}

console.log(`\n标识规则验证：通过 ${pass}，失败 ${failures.length}`);
if (failures.length) {
  for (const f of failures) console.log('  - ' + f);
  process.exit(1);
}