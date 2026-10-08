// 占位符安全验证：格式化器绝不能破坏 {{name}}（唯一受支持的参数语法）
// 背景：后端 MUSTACHE_PATTERN = \{\{(name)\}\}(?:::(type))?
//       一旦格式化在花括号两侧插入空格，占位符正则就匹配不上，
//       SQL 里的 {{ name }} 会作为字面量发给数据库 → 语法错误。
//
// 本脚本分两组断言：
//   A 组 {{name}} —— 受支持语法，必须与后端正则严格等价（漏一个就是运行时炸）
//   B 组 :name   —— 已废弃语法，后端会抛 400。这里只断言「格式化器不把它拆开」，
//                   目的是让老 SQL 在编辑器里保持可读、能一眼看出要改成 {{name}}，
//                   断成 `: name` 反而更难定位。不是断言它能跑。
import { formatSql, tokenize } from '../frontend/src/utils/sqlFormatter.ts';

const MUSTACHE = /\{\{([a-zA-Z_][a-zA-Z0-9_]*)\}\}(?:::([a-zA-Z_][a-zA-Z0-9_]*))?/g;
const COLON = /(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)/g;

const mustacheCases = [
  'select * from invoice where no = {{no}} and amount > {{amt}}',
  'select * from invoice where created_at between {{d1}} and {{d2}}',
  'select {{col}} from {{schema}}.{{table}} limit 10',
  'with c as (select id from t where x = {{x}}::text) select * from c where y = {{y}}',
  'select {{a}}{{b}}{{c}} from t',
];

const legacyCases = [
  'select * from invoice where status = :status and buyer = :buyer',
  'select {{col}} from {{schema}}.{{table}} limit :n',
  'with c as (select id from t where x = {{x}}::text) select * from c where y = :y',
  "select * from t where kind = 'type:amount'",
];

let fail = 0;

const fingerprint = (s) =>
  tokenize(s).filter((t) => t.type !== 'ws' && t.type !== 'comment')
    .map((t) => `${t.type}:${t.keyword ? t.value.toUpperCase() : t.value}`).join('|');

function check(label, sql, patterns) {
  const out = formatSql(sql);
  const problems = [];

  for (const [name, re, key] of patterns) {
    const before = [...sql.matchAll(re)].map((m) => (key ? m[1] + (m[2] ? '::' + m[2] : '') : m[1]));
    const after = [...out.matchAll(re)].map((m) => (key ? m[1] + (m[2] ? '::' + m[2] : '') : m[1]));
    if (JSON.stringify(before) !== JSON.stringify(after)) {
      problems.push(`${name} 被破坏 before=${JSON.stringify(before)} after=${JSON.stringify(after)}`);
    }
  }

  if (fingerprint(sql) !== fingerprint(out)) {
    problems.push('token 序列变了（指纹兜底本应原样返回）');
  }

  const good = problems.length === 0;
  if (!good) fail++;
  console.log(`${good ? 'PASS' : 'FAIL'}  [${label}] ${sql}`);
  if (!good) problems.forEach((p) => console.log(`      ${p}`));
  console.log('      --- 输出 ---');
  console.log(out.split('\n').map((l) => '      | ' + l).join('\n'));
}

console.log('== A 组：受支持的 {{name}} ==');
for (const sql of mustacheCases) {
  check('{{}}', sql, [['{{}}', MUSTACHE, true]]);
}

console.log('\n== B 组：已废弃的 :name（只断言不被拆开） ==');
for (const sql of legacyCases) {
  check(':name', sql, [[':name', COLON, false]]);
}

console.log(fail === 0 ? '\n占位符全部安全\n' : `\n${fail} 条被破坏\n`);
process.exit(fail ? 1 : 0);