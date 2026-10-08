// SQL 格式化器验证：语义等价 + 幂等 + 边界
// 用法：node --experimental-strip-types scripts/verify-sql-format.mjs
import { formatSql, tokenize } from '../frontend/src/utils/sqlFormatter.ts';

let fail = 0;
const ok = (cond, msg) => {
  if (cond) console.log(`  PASS  ${msg}`);
  else { console.log(`  FAIL  ${msg}`); fail++; }
};

// 语义指纹：忽略空白 / 注释，关键字统一大写
const fp = (sql) =>
  tokenize(sql)
    .filter((t) => t.type !== 'ws' && t.type !== 'comment')
    .map((t) => `${t.type}:${t.keyword ? t.value.toUpperCase() : t.value}`)
    .join('');

console.log('\n=== 1. 语义等价（格式化前后 token 序列必须一致）===');
const cases = [
  'select a,b,c from t where x=1',
  "select o.no, o.amount, c.name as cust from invoice_order o left join customer c on c.id=o.cust_id where o.status='PAID' and o.amount>100 order by o.no desc limit 50",
  "select date_trunc('month', created_at) m, count(*) cnt from orders group by 1 having count(*)>3",
  "select case when a>1 then 'x,y' else 'z' end as v from t",
  'with cte as (select id, sum(v) s from t group by id) select * from cte where s > 10',
  'select a || b, c from t union all select d, e from u',
  "insert into t (a,b) values (1,'x'), (2,'y')",
  "select \"weird,col\" from \"my table\"",
  'select count(a, b), f(x, y) from t',
  'select a from t where name like \'%a,b%\' and x in (1,2,3)',
  'SELECT DISTINCT a, b FROM t',
  '',
  '   ',
  "select '未闭合",
  '-- 只有注释\nselect 1',
  'select /* 块注释 */ a from t',
  // 占位符：必须原样保留，否则后端的 {{name}} 正则会失配
  'select * from invoice where no = {{no}} and amount > {{amt}}',
  'select {{col}} from {{schema}}.{{table}} limit 10',
  'select id from t where x = {{x}}::text',
  // 已废弃的 :name 仍保持原子（不被拆成 `: name`），便于用户定位改写
  'select * from invoice where status = :status and buyer = :buyer',
  "select id from t where kind = 'type:amount'",
];
for (const c of cases) {
  const out = formatSql(c);
  ok(fp(c) === fp(out) || c.trim() === '', `等价: ${JSON.stringify(c.slice(0, 46))}`);
}

console.log('\n=== 2. 幂等（format(format(x)) === format(x)）===');
for (const c of cases) {
  if (!c.trim()) continue;
  const once = formatSql(c);
  const twice = formatSql(once);
  ok(once === twice, `幂等: ${JSON.stringify(c.slice(0, 40))}`);
}

console.log('\n=== 3. 保守退出（不该动的一律不动）===');
ok(formatSql("select '未闭合") === "select '未闭合", '未闭合引号 → 原样');
ok(formatSql('-- 注释\nselect 1').startsWith('--'), '含注释 → 原样');
ok(formatSql('') === '', '空串 → 空串');

console.log('\n=== 4. 占位符原样保留（后端正则依赖它）===');
for (const c of [
  'select * from t where a = {{a}} and b = :b',
  'select {{s}}.{{t}} from x',
  'select * from t where v = {{v}}::int',
]) {
  const out = formatSql(c);
  const ph = (s) => [...s.matchAll(/\{\{[a-zA-Z_][a-zA-Z0-9_]*\}\}(?:::[a-zA-Z_][a-zA-Z0-9_]*)?/g)].map((m) => m[0]);
  const col = (s) => [...s.matchAll(/(?<!:):[a-zA-Z_][a-zA-Z0-9_]*/g)].map((m) => m[0]);
  ok(
    JSON.stringify(ph(c)) === JSON.stringify(ph(out)) && JSON.stringify(col(c)) === JSON.stringify(col(out)),
    `占位符不变: ${JSON.stringify(c.slice(0, 44))}`,
  );
}

console.log('\n=== 5. 实际输出样张 ===');
const demo = "select o.no,o.amount,c.name cust from invoice_order o left join customer c on c.id=o.cust_id where o.status='PAID' order by o.no desc limit 20";
console.log(formatSql(demo).split('\n').map((l) => '    | ' + l).join('\n'));

const demo2 = "with recent as (select id,sum(amount) s from orders where created_at>='2024-01-01' group by id) select r.id,r.s,c.name from recent r join customer c on c.id=r.id where r.s>1000 order by r.s desc";
console.log(formatSql(demo2).split('\n').map((l) => '    | ' + l).join('\n'));

console.log(fail === 0 ? '\n全部通过\n' : `\n${fail} 项失败\n`);
process.exit(fail ? 1 : 0);