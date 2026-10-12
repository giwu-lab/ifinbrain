/**
 * 验证核心架构假设：沙箱的 get 穿透读取能自动接入 Vue 依赖追踪，
 * 不需要像 Barda 那样自己维护 Node/dependMap。
 */
import { reactive, computed, watchEffect, isRef } from 'vue';
import { evalText } from '../../src/utils/expr/eval';
import { renameFieldKey } from '../../src/utils/expr/deps';
import { useFormExpr } from '../../src/composables/useFormExpr';

let pass = 0, fail = 0;
function eq(name: string, a: unknown, e: unknown) {
  if (JSON.stringify(a) === JSON.stringify(e)) { pass++; console.log(`  ok   ${name}`); }
  else { fail++; console.log(`  FAIL ${name} 期望 ${JSON.stringify(e)} 实际 ${JSON.stringify(a)}`); }
}
function ok(name: string, c: boolean, x = '') {
  if (c) { pass++; console.log(`  ok   ${name}`); } else { fail++; console.log(`  FAIL ${name} ${x}`); }
}

console.log('\n[1] computed 自动重算（无需手写依赖图）');
const formData = reactive<Record<string, any>>({ name: '张三', age: 30 });

const greeting = computed(() => evalText('你好 {{$form.name}}，今年 {{$form.age}} 岁', { $form: formData }));
eq('初始值', greeting.value, '你好 张三，今年 30 岁');
formData.name = '李四';
eq('改 name 后自动重算', greeting.value, '你好 李四，今年 30 岁');
formData.age = 31;
eq('改 age 后自动重算', greeting.value, '你好 李四，今年 31 岁');
ok('computed 保持惰性（是 ref）', isRef(greeting));

console.log('\n[2] 无依赖的字段不订阅任何东西');
const plain = computed(() => evalText('静态文案', { $form: formData }));
eq('静态串求值', plain.value, '静态文案');
let plainRuns = 0;
watchEffect(() => { plainRuns++; plain.value; });
formData.name = '王五';
ok('静态 computed 不因无关字段变化而重算', plainRuns === 1, `runs=${plainRuns}`);

console.log('\n[3] 多层穿透读取也能追踪');
const nested = reactive({ user: { city: '杭州' } });
const cityLabel = computed(() => evalText('城市：{{$form.user.city}}', { $form: nested }));
eq('嵌套初始值', cityLabel.value, '城市：杭州');
nested.user.city = '北京';
eq('深层变更后重算', cityLabel.value, '城市：北京');

console.log('\n[4] useFormExpr 的 resolve() 引用稳定性');
formData.name = '张三';   // 复位：上一节改过 name
const expr = useFormExpr(formData);
const field: any = { id: 'f1', type: 'TEXT', key: 'note', label: '给 {{$form.name}} 的备注', placeholder: '', help: '' };
const r1 = expr.resolve(field);
const r2 = expr.resolve(field);
ok('解析值变化前引用稳定（避免无谓子组件重渲染）', r1 === r2);
eq('解析后的 label', r1.label, '给 张三 的备注');
formData.name = '李四';
const r3 = expr.resolve(field);
ok('解析值变化后换新引用', r3 !== r1);
eq('新 label', r3.label, '给 李四 的备注');
ok('未污染原 schema', field.label === '给 {{$form.name}} 的备注');

console.log('\n[5] 无表达式字段走零开销路径');
const plainField: any = { id: 'f2', type: 'TEXT', key: 'x', label: '纯静态', placeholder: '提示', help: '' };
ok('原样返回同一引用', expr.resolve(plainField) === plainField);
ok('hasAnyExpr 判定正确', expr.hasAnyExpr(field) && !expr.hasAnyExpr(plainField));

console.log('\n[6] 设计期校验（与运行时数据无关）');
const fields: any[] = [
  { id: 'a', type: 'TEXT', key: 'userName', label: '姓名', placeholder: '', help: '' },
  { id: 'b', type: 'TEXT', key: 'note', label: '给 {{$form.userName}} 的备注', placeholder: '', help: '' },
  { id: 'c', type: 'TEXT', key: 'oops', label: '{{$form.ghost}} 的备注', placeholder: '', help: '' },
  { id: 'd', type: 'TEXT', key: 'broken', label: '{{ $form.((( }}', placeholder: '', help: '' },
];
const issues = expr.issues(fields);
eq('检出 2 个问题（1 未定义引用 + 1 语法错误）', issues.length, 2);
eq('未定义引用被识别', issues[0].kind, 'undefined-ref');
eq('未定义引用的 key', issues[0].detail, 'ghost');
eq('语法错误被识别', issues[1].kind, 'eval-error');
ok('字段 b 的引用合法，不报错', !issues.some((i) => i.fieldId === 'b'));
eq('可用引用清单', expr.availableRefs(fields), ['$form.userName', '$form.note', '$form.oops', '$form.broken']);

console.log('\n[7] 条件逻辑：visibleWhen / disabledWhen / requiredWhen');
const cond = useFormExpr(formData);
const condFields: any[] = [
  { id: 'x', type: 'TEXT', key: 'taxId', label: '税号', visibleWhen: '{{ $form.needInvoice === true }}' },
  { id: 'y', type: 'TEXT', key: 'remark', label: '备注', visibleWhen: '{{ $form.needInvoice === true }}' },
];
formData.needInvoice = false;
ok('条件为 false → 不显示', !cond.isVisible(condFields[0]));
eq('两个字段同步隐藏', [cond.isVisible(condFields[0]), cond.isVisible(condFields[1])], [false, false]);
formData.needInvoice = true;
eq('条件为 true → 显示（响应式自动跟上）', [cond.isVisible(condFields[0]), cond.isVisible(condFields[1])], [true, true]);
eq('未配 visibleWhen = 恒显示', cond.isVisible({ id: 'z', type: 'TEXT', key: 'k', label: 'k' } as any), true);

console.log('\n[8] 条件逻辑：失败一律按「不成立」（宁可少显示，不静默算错）');
ok('引用不存在字段 → false', !cond.isVisible({ id: 'p', type: 'TEXT', key: 'k', label: 'l', visibleWhen: '{{ $form.nope }}' } as any));
ok('语法错误 → false', !cond.isVisible({ id: 'q', type: 'TEXT', key: 'k', label: 'l', visibleWhen: '{{ ((( }}' } as any));
ok('非布尔真值（字符串）→ false', !cond.isVisible({ id: 'r', type: 'TEXT', key: 'k', label: 'l', visibleWhen: '{{ "abc" }}' } as any));
ok('数字 1 不算 true（严格 === true）', !cond.isVisible({ id: 's', type: 'TEXT', key: 'k', label: 'l', visibleWhen: '{{ 1 }}' } as any));

console.log('\n[9] 条件逻辑：required 与 disabled');
const reqField: any = { id: 'rr', type: 'TEXT', key: 'k', label: 'l', required: false };
eq('未配 requiredWhen → 沿用字段级 required', cond.isRequired(reqField), false);
reqField.required = true;
eq('字段级 required=true 即必填', cond.isRequired(reqField), true);
reqField.required = false;
reqField.requiredWhen = '{{ $form.age >= 18 }}';
formData.age = 30;
eq('requiredWhen 成立 → 必填', cond.isRequired(reqField), true);
formData.age = 10;
eq('requiredWhen 不成立 → 非必填', cond.isRequired(reqField), false);

const disField: any = { id: 'dd', type: 'TEXT', key: 'k', label: 'l' };
eq('未配 disabledWhen → 可编辑', cond.isDisabled(disField), false);
disField.disabledWhen = '{{ $form.locked }}';
formData.locked = true;
eq('disabledWhen 成立 → 禁用', cond.isDisabled(disField), true);
ok('禁用不影响可见性（两者正交）', cond.isVisible(disField));

console.log('\n[10] 条件属性也纳入设计期校验与引用重写');
const condIssues = cond.issues([
  { id: 'a', type: 'TEXT', key: 'userName', label: '姓名' },
  { id: 'b', type: 'TEXT', key: 'note', label: '备注', visibleWhen: '{{ $form.whoKnows }}' } as any,
]);
eq('布尔条件里的未定义引用同样被检出', condIssues.length, 1);
eq('定位到正确的属性', condIssues[0].attr, 'visibleWhen');
eq('引用重写覆盖布尔条件属性',
  renameFieldKey('{{ $form.a }}', 'a', 'b') && cond.issues([
    { id: 'a', type: 'TEXT', key: 'a', label: 'A' },
    { id: 'b', type: 'TEXT', key: 'b', label: 'B', visibleWhen: '{{ $form.b === 1 }}' } as any,
  ]).length, 0);

console.log(`\n===== 通过 ${pass} / 失败 ${fail} =====\n`);
process.exit(fail ? 1 : 0);