/**
 * 表单栅格布局模型的行为验证（临时脚本，验证后删除）。
 * 覆盖：fullWidth→栅格迁移 / 半宽并排 / 整行元素强制满宽 / 找空位 /
 *       夹取边界 / 重叠检测 / CSS Grid 换算。
 */
import {
  DEFAULT_GRID, GRID_COLS, clampLayout, findFreeSlot, hasCollision,
  layoutRows, migrateFields, overlaps, toGridPlacement, validateLayoutOf,
  type FormGridLayout,
} from '../../src/utils/form/layout';

let pass = 0, fail = 0;

/**
 * 键序无关的深比较。
 * 直接 JSON.stringify 会因为 {w,h,x,y} 与 {x,y,w,h} 判不等，
 * 但两者语义完全一样 —— 那是测试的假阳性，不是代码的 bug。
 */
function canon(v: any): any {
  if (Array.isArray(v)) return v.map(canon);
  if (v && typeof v === 'object') {
    return Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon(v[k])]));
  }
  return v;
}

function eq(name: string, a: unknown, e: unknown) {
  if (JSON.stringify(canon(a)) === JSON.stringify(canon(e))) { pass++; console.log(`  ok   ${name}`); }
  else { fail++; console.log(`  FAIL ${name}\n         期望 ${JSON.stringify(canon(e))}\n         实际 ${JSON.stringify(canon(a))}`); }
}
function ok(name: string, c: boolean, x = '') {
  if (c) { pass++; console.log(`  ok   ${name}`); } else { fail++; console.log(`  FAIL ${name} ${x}`); }
}

/** 简化构造：只要 id/type/fullWidth，layout 由迁移生成 */
const f = (type: string, fullWidth?: boolean, key = '') =>
  ({ id: key || Math.random().toString(36).slice(2), type, key, label: key, fullWidth } as any);

console.log('\n[1] 迁移：fullWidth → 栅格');
const all = migrateFields([f('TEXT', true, 'a'), f('TEXT', false, 'b'), f('TEXT', false, 'c')]);
eq('fullWidth=true 占满整行', all[0].layout, { x: 0, y: 0, w: 24, h: 1 });
eq('第一个半宽靠左', all[1].layout, { x: 0, y: 1, w: 12, h: 1 });
eq('第二个半宽靠右并排', all[2].layout, { x: 12, y: 1, w: 12, h: 1 });

console.log('\n[2] 迁移：三个半宽字段自动换行');
const three = migrateFields([f('TEXT', false, 'a'), f('TEXT', false, 'b'), f('TEXT', false, 'c')]);
eq('第三个换到下一行左侧', three[2].layout, { x: 0, y: 1, w: 12, h: 1 });

console.log('\n[3] 迁移：DIVIDER / TABLE 强制满宽（半宽没有意义）');
const forced = migrateFields([
  f('TEXT', false, 'a'),
  f('DIVIDER', false, 'div'),
  f('TABLE', false, 'tbl'),
  f('TEXT', false, 'b'),
]);
eq('DIVIDER 强制整行', forced[1].layout, { x: 0, y: 1, w: 24, h: 1 });
eq('TABLE 强制整行', forced[2].layout, { x: 0, y: 2, w: 24, h: 1 });
eq('整行元素会打断并排', forced[3].layout, { x: 0, y: 3, w: 12, h: 1 });

console.log('\n[4] 迁移：已有 layout 的字段被保留且夹回合法范围');
const kept = migrateFields([
  { id: 'a', type: 'TEXT', key: 'a', label: 'a', layout: { x: 4, y: 2, w: 8, h: 1 } } as any,
  { id: 'b', type: 'TEXT', key: 'b', label: 'b', layout: { x: 20, y: 0, w: 8, h: 1 } } as any,
]);
eq('合法坐标原样保留', kept[0].layout, { x: 4, y: 2, w: 8, h: 1 });
eq('越界的 x 被夹回 (20+8>24)', kept[1].layout, { x: 16, y: 0, w: 8, h: 1 });

console.log('\n[5] 迁移是幂等的（重复加载不会越滚越乱）');
const twice = migrateFields(migrateFields([f('TEXT', true, 'a'), f('TEXT', false, 'b'), f('TEXT', false, 'c')]));
eq('二次迁移结果不变', twice.map(x => x.layout), all.map(x => x.layout));

console.log('\n[6] clampLayout：永远至少 1 格，不出画布');
eq('负数被夹到 0', clampLayout({ x: -5, y: -3, w: 10, h: 2 }), { x: 0, y: 0, w: 10, h: 2 });
eq('宽度超界被夹到列数', clampLayout({ x: 0, y: 0, w: 999, h: 1 }).w, GRID_COLS);
eq('宽度 0 被抬到 1', clampLayout({ x: 0, y: 0, w: 0, h: 1 }).w, 1);
eq('高度 0 被抬到 1', clampLayout({ x: 0, y: 0, w: 1, h: 0 }).h, 1);
eq('y 没有上界（表单可无限长）', clampLayout({ x: 0, y: 9999, w: 1, h: 1 }).y, 9999);

console.log('\n[7] 找空位：跳过已占区域');
const taken: FormGridLayout[] = [{ x: 0, y: 0, w: 24, h: 1 }];
eq('第一行满 → 落到第二行', findFreeSlot(taken, 24, 1), { x: 0, y: 1, w: 24, h: 1 });
eq('空画布 → 落在原点', findFreeSlot([], 12, 1), { x: 0, y: 0, w: 12, h: 1 });
const half = [{ x: 0, y: 0, w: 12, h: 1 }];
eq('右侧还有空位 → 并排', findFreeSlot(half, 12, 1), { x: 12, y: 0, w: 12, h: 1 });
eq('宽度超过画布 → 收到列数', findFreeSlot([], 999, 1).w, GRID_COLS);

console.log('\n[8] 重叠检测');
ok('完全重叠', overlaps({ x: 0, y: 0, w: 12, h: 1 }, { x: 0, y: 0, w: 12, h: 1 }));
ok('部分重叠', overlaps({ x: 0, y: 0, w: 12, h: 1 }, { x: 6, y: 0, w: 12, h: 1 }));
ok('相邻不重叠（边贴边）', !overlaps({ x: 0, y: 0, w: 12, h: 1 }, { x: 12, y: 0, w: 12, h: 1 }));
ok('上下相邻不重叠', !overlaps({ x: 0, y: 0, w: 24, h: 1 }, { x: 0, y: 1, w: 24, h: 1 }));
ok('跨行重叠（h=2 撞上两行的单行）', overlaps({ x: 0, y: 0, w: 12, h: 2 }, { x: 0, y: 1, w: 24, h: 1 }));

console.log('\n[9] hasCollision：不会跟自己撞');
const list: any[] = [
  { id: 'a', key: 'a', label: 'A', layout: { x: 0, y: 0, w: 12, h: 1 } },
  { id: 'b', key: 'b', label: 'B', layout: { x: 12, y: 0, w: 12, h: 1 } },
];
ok('移到自身原位不算碰撞', !hasCollision(list, 'a', { x: 0, y: 0, w: 12, h: 1 }));
ok('移到 B 的位置算碰撞', !!hasCollision(list, 'a', { x: 12, y: 0, w: 12, h: 1 }));
eq('找出的是被撞的那个字段', hasCollision(list, 'a', { x: 12, y: 0, w: 12, h: 1 })?.key, 'b');

console.log('\n[10] validateLayoutOf');
ok('正常位置通过', validateLayoutOf(list, 'a', { x: 0, y: 2, w: 12, h: 1 }).ok);
ok('越界被拦', !validateLayoutOf(list, 'a', { x: 20, y: 0, w: 12, h: 1 }).ok);
ok('负行被拦', !validateLayoutOf(list, 'a', { x: 0, y: -1, w: 12, h: 1 }).ok);
ok('碰撞时给出原因', (validateLayoutOf(list, 'a', { x: 12, y: 0, w: 12, h: 1 }).reason ?? '').includes('B'));

console.log('\n[11] 画布高度随字段数增长');
eq('按最大 y+h 取值', layoutRows(list), 1);
eq('有第二行就是 2', layoutRows([...list, { id: 'c', layout: { x: 0, y: 5, w: 1, h: 1 } } as any]), 6);
eq('h=2 的字段占两行', layoutRows([{ id: 'c', layout: { x: 0, y: 0, w: 1, h: 2 } } as any]), 2);

console.log('\n[12] CSS Grid 换算');
const pl = toGridPlacement({ x: 4, y: 2, w: 8, h: 1 }, DEFAULT_GRID);
eq('gridColumn 是 1-based 且含 span', pl.gridColumn, '5 / span 8');
eq('gridRow 同样 1-based', pl.gridRow, '3 / span 1');
ok('带 minWidth:0 防止内容撑破格子', pl.minWidth === '0');
eq('跨两行', toGridPlacement({ x: 0, y: 0, w: 24, h: 2 }).gridRow, '1 / span 2');

console.log(`\n===== 通过 ${pass} / 失败 ${fail} =====\n`);
process.exit(fail ? 1 : 0);