/**
 * 表格横向滚动条判定规则验证。
 *
 * 覆盖：最小宽度累加 / width 缺失与非法值回退 / 序号列与操作列 / 容器未量到 /
 *       边界差值（几像素内不判溢出）/ 脏数据兜底。
 */
import {
  ACTIONS_COL_WIDTH,
  DEFAULT_COL_WIDTH,
  INDEX_COL_WIDTH,
  needsScrollX,
  tableMinWidth,
} from '../../src/utils/form/tableScroll';

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

const col = (width?: number | null) => ({ width });

console.log('\n[1] 最小宽度累加');
{
  eq('没有列 = 0', tableMinWidth({ columns: [] }), 0);
  eq('三列无宽度 → 3 × 140', tableMinWidth({ columns: [col(), col(), col()] }), 420);
  eq('设了 width 的按 width 算', tableMinWidth({ columns: [col(200), col(100)] }), 300);
  eq('有的设有的没设混合', tableMinWidth({ columns: [col(200), col()] }), 340);
}
{
  eq('自定义默认列宽生效', tableMinWidth({ columns: [col(), col()], defaultColWidth: 100 }), 200);
  eq('默认常量就是 140', DEFAULT_COL_WIDTH, 140);
}

console.log('\n[2] 非法宽度回退到最小宽');
{
  // width 是 number | null | undefined，JSON 里可能是 null；0/负数是脏数据
  eq('null 回退', tableMinWidth({ columns: [col(null), col(null)] }), 280);
  eq('undefined 回退', tableMinWidth({ columns: [col(undefined), col(undefined)] }), 280);
  eq('0 回退', tableMinWidth({ columns: [col(0)] }), 140);
  eq('负数回退', tableMinWidth({ columns: [col(-50)] }), 140);
  eq('字符串宽度也回退', tableMinWidth({ columns: [{ width: '300' as unknown as number }] }), 140);
}

console.log('\n[3] 序号列与操作列');
{
  // 输入模式：48 序号 + 104 操作
  eq('输入模式三列', tableMinWidth({
    columns: [col(), col(), col()],
    indexColWidth: INDEX_COL_WIDTH,
    actionsColWidth: ACTIONS_COL_WIDTH,
  }), 420 + 48 + 104);
  eq('只读没有操作列', tableMinWidth({
    columns: [col(), col(), col()],
    indexColWidth: INDEX_COL_WIDTH,
    actionsColWidth: 0,
  }), 420 + 48);
  eq('展示模式两侧都没有', tableMinWidth({ columns: [col(), col()] }), 280);
  eq('默认列宽常量', [INDEX_COL_WIDTH, ACTIONS_COL_WIDTH], [48, 104]);
}

console.log('\n[4] 容器还没量到 → 先按需要滚动处理');
{
  ok('容器宽 0 → 需要滚动', needsScrollX(280, 0) === true);
  ok('容器宽为负 → 需要滚动', needsScrollX(280, -10) === true);
}

console.log('\n[5] 溢出判定与边界');
{
  ok('列 600 容器 1000 → 不需要', needsScrollX(600, 1000) === false);
  ok('列 1000 容器 600 → 需要', needsScrollX(1000, 600) === true);
  ok('刚好等于容器 → 不需要', needsScrollX(600, 600) === false);
  ok('差 2px → 不判溢出（边框噪声）', needsScrollX(602, 600) === false);
  ok('差 5px → 判溢出', needsScrollX(605, 600) === true);
}

console.log('\n[6] 真实场景');
{
  // 20 列全无宽度，输入模式：20×140 + 48 + 104 = 2952，容器 600 → 必须滚动
  const wide = tableMinWidth({
    columns: Array.from({ length: 20 }, () => col()),
    indexColWidth: INDEX_COL_WIDTH,
    actionsColWidth: ACTIONS_COL_WIDTH,
  });
  eq('20 列输入模式的最小宽度', wide, 2952);
  ok('600px 容器里需要滚动', needsScrollX(wide, 600));

  // 3 列输入模式：3×140+48+104 = 572，容器 600 → 放得下
  const narrow = tableMinWidth({
    columns: [col(), col(), col()],
    indexColWidth: INDEX_COL_WIDTH,
    actionsColWidth: ACTIONS_COL_WIDTH,
  });
  eq('3 列输入模式的最小宽度', narrow, 572);
  ok('600px 容器里放得下', !needsScrollX(narrow, 600));
  ok('400px 容器里放不下', needsScrollX(narrow, 400));
}

console.log('\n[7] 脏数据不炸');
{
  eq('columns 为 null 也不炸', tableMinWidth({ columns: null as never }), 0);
  // 列对象为 null：不能崩，且它仍然占一个列位（按最小宽算）
  eq('列对象为 null 也不炸', tableMinWidth({ columns: [null as never, col()] }), 280);
}

console.log(`\n表格横向滚动验证：通过 ${pass}，失败 ${failures.length}`);
if (failures.length) {
  for (const f of failures) console.log('  - ' + f);
  process.exit(1);
}
