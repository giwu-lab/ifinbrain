/**
 * M1 表达式内核行为验证（临时脚本，验证后删除）。
 * 覆盖：段解析 / 嵌套花括号 / 单表达式保留原始类型 / 静态串零开销 /
 *       沙箱黑名单 / 黑洞吞访问 / 引用重命名的段边界 / 未定义引用检测。
 */
import { evalDefault, evalText, stripExpressions } from '../../src/utils/expr/eval';
import { getDynamicStringSegments } from '../../src/utils/expr/segments';
import { renameFieldKey, referencedFieldKeys, undefinedFieldRefs } from '../../src/utils/expr/deps';

let pass = 0, fail = 0;
function eq(name: string, actual: unknown, expected: unknown) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) { pass++; console.log(`  ok   ${name}`); }
  else { fail++; console.log(`  FAIL ${name}\n         期望 ${e}\n         实际 ${a}`); }
}
function ok(name: string, cond: boolean, extra = '') {
  if (cond) { pass++; console.log(`  ok   ${name}`); }
  else { fail++; console.log(`  FAIL ${name} ${extra}`); }
}

const ctx = { $form: { name: '张三', age: 30, tags: ['a', 'b'], nested: { deep: 1 } } };

console.log('\n[1] 段解析');
eq('纯静态', getDynamicStringSegments('hello'), ['hello']);
eq('单个动态段', getDynamicStringSegments('{{a}}'), ['{{a}}']);
eq('前后夹静态', getDynamicStringSegments('你好 {{a}} 呀'), ['你好 ', '{{a}}', ' 呀']);
eq('多个动态段', getDynamicStringSegments('{{a}}-{{b}}'), ['{{a}}', '-', '{{b}}']);
eq('嵌套花括号不被截断',
  getDynamicStringSegments('{{ f.n ? {x:1} : {} }}'), ['{{ f.n ? {x:1} : {} }}']);
eq('动态段里的对象字面量', getDynamicStringSegments('{{ {a:1,b:2} }}'), ['{{ {a:1,b:2} }}']);

console.log('\n[2] 求值：类型保持');
eq('字符串插值', evalDefault('你好 {{$form.name}}', ctx).value, '你好 张三');
eq('单段返回原始字符串', evalDefault('{{$form.name}}', ctx).value, '张三');
eq('单段返回数字(非字符串)', evalDefault('{{$form.age}}', ctx).value, 30);
ok('单段返回数组而非 [object Object]',
  Array.isArray((evalDefault('{{$form.tags}}', ctx).value as unknown)));
eq('嵌套取值', evalDefault('{{$form.nested.deep}}', ctx).value, 1);
eq('算术', evalDefault('{{$form.age * 2}}', ctx).value, 60);
eq('宿主全局可用', evalDefault('{{ Math.max($form.age, 18) }}', ctx).value, 30);
eq('多段拼接', evalDefault('{{$form.name}}（{{$form.age}}岁）', ctx).value, '张三（30岁）');

console.log('\n[3] 求值：降级不抛');
const bad = evalDefault('{{ $form.nope.deep() }}', ctx);
ok('错误不抛出，返回空串', bad.value === '' && !!bad.msg, `value=${JSON.stringify(bad.value)}`);
eq('失败段被标记', bad.extra?.segments?.[0]?.success, false);
eq('evalText 失败时保留静态文本', evalText('你好 {{$form.zzz}}', ctx), '你好 ');
eq('stripExpressions', stripExpressions('你好 {{$form.name}}!'), '你好 !');
eq('未定义变量不报错但返回空', evalDefault('{{$form.ghost}}', ctx).value, undefined);

console.log('\n[4] 沙箱');
ok('fetch 被黑洞吸收而非抛错', (() => {
  try { evalDefault('{{ fetch("http://x") }}', ctx); return true; } catch { return false; }
})());
ok('document 被黑洞吸收', (() => {
  try { evalDefault('{{ document.cookie }}', ctx); return true; } catch { return false; }
})());
ok('表达式可读宿主全局（未过度封锁）', evalDefault('{{ typeof JSON }}', ctx).value === 'object');

console.log('\n[5] 只读视图：防静默污染业务数据');
// 注意：evalDefault 按设计吞掉异常并转成 .msg，所以这里断言 hasError()，
// 不能用 try/catch —— 那样测的是「有没有抛」而不是「沙箱有没有拦住」。
const setVar = evalDefault('{{ $form = 1 }}', ctx);
ok('改写 $form 标识符被拒', setVar.hasError(), `msg=${setVar.msg}`);
const setProp = evalDefault('{{ $form.name = "李四" }}', ctx);
ok('改写 $form 属性被拒', setProp.hasError(), `msg=${setProp.msg}`);
const mutateArr = evalDefault('{{ $form.tags.push("c") }}', ctx);
ok('改写 $form 内嵌数组被拒', mutateArr.hasError(), `msg=${mutateArr.msg}`);
const delProp = evalDefault('{{ delete $form.name }}', ctx);
ok('delete $form 属性被拒', delProp.hasError());
eq('上下文完全未被污染', ctx.$form.name, '张三');
eq('内嵌数组未被污染', ctx.$form.tags, ['a', 'b']);

console.log('\n[6] 只读视图不影响读取');
eq('只读视图下取嵌套值', evalDefault('{{$form.nested.deep}}', ctx).value, 1);
eq('只读视图下数组仍可用', (evalDefault('{{$form.tags.length}}', ctx).value as number), 2);
eq('只读视图下数组方法可用', evalDefault('{{$form.tags.join(",")}}', ctx).value, 'a,b');
eq('只读视图下数组返回真数组', Array.isArray(evalDefault('{{$form.tags}}', ctx).value), true);
eq('只读视图下对象可序列化', evalDefault('{{ JSON.stringify($form.nested) }}', ctx).value, '{"deep":1}');

console.log('\n[7] 显式放行上下文改写（派生字段场景）');
const writable = { $form: { a: 1, b: 2 } };
// 单表达式模式不支持分号多语句，必须整体是一个表达式
const derived = evalDefault('{{ ($form.total = $form.a + $form.b) }}', writable,
  { allowContextMutation: true });
eq('allowContextMutation 时可写', derived.value, 3);
eq('放行后确实写入了', writable.$form.total, 3);
const arr = { $form: { list: [1] } };
eq('放行后可改数组', evalDefault('{{ ($form.list.push(2)) }}', arr,
  { allowContextMutation: true }).value, 2);

console.log('\n[8] 引用分析与重命名');
eq('提取字段引用', referencedFieldKeys('共 {{$form.count}} 项'), ['count']);
eq('多引用去重', referencedFieldKeys('{{$form.a}}{{$form.a}}{{$form.b}}'), ['a', 'b']);
eq('非 $form 标识符不计入', referencedFieldKeys('{{ Math.max(1,2) }}'), []);
eq('未定义引用检出', undefinedFieldRefs('{{$form.ghost}}', ['name', 'age']), ['ghost']);
eq('已定义引用不报', undefinedFieldRefs('{{$form.name}}', ['name']), []);

eq('重命名 段内引用', renameFieldKey('共 {{$form.amount}} 元', 'amount', 'amount_copy'), '共 {{$form.amount_copy}} 元');
eq('重命名 不误伤前缀相同的 key', renameFieldKey('{{$form.amountOther}}', 'amount', 'amount_copy'), '{{$form.amountOther}}');
eq('重命名 不误伤子串', renameFieldKey('{{$form.xamount}}', 'amount', 'amount_copy'), '{{$form.xamount}}');
eq('重命名 不动静态文本', renameFieldKey('amount 是 {{$form.amount}}', 'amount', 'amount_copy'), 'amount 是 {{$form.amount_copy}}');
eq('重命名 同一 key 多处', renameFieldKey('{{$form.a}}和{{$form.a}}', 'a', 'b'), '{{$form.b}}和{{$form.b}}');

console.log(`\n===== 通过 ${pass} / 失败 ${fail} =====\n`);
process.exit(fail ? 1 : 0);