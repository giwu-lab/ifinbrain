/**
 * i18n key 全路径核对：代码里 t('a.b.c') 的每个 key，都必须在 zh-CN / en-US 里
 * 真实存在（而不是「某处有个同名叶子」）。
 *
 * <p>为什么不用「叶子名」匹配：key 多了同名叶子会互相顶包，
 * `bi.dash.pubDir` 存在就能把 `report.pubDir` 的缺失掩盖掉 ——
 * 之前就是这么漏掉一个悬空 key 的。
 *
 * <p>用法：node scripts/i18n-check.mjs [files...]
 * 依赖：先 npx esbuild 把两个 locale 打成 .i18n-check/ 下的临时 mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const zhPath = process.argv[2];
const enPath = process.argv[3];
const targets = process.argv.slice(4);

if (!zhPath || !enPath || targets.length === 0) {
  console.error('usage: node i18n-check.mjs <zh.mjs> <en.mjs> <file...>');
  process.exit(2);
}

const zh = (await import(pathToUrl(zhPath))).default;
const en = (await import(pathToUrl(enPath))).default;

function pathToUrl(p) {
  return 'file:///' + path.resolve(p).replace(/\\/g, '/');
}

/** 逐段下钻：任一段不存在、或中间段不是对象，就返回 null */
function lookup(obj, dotted) {
  let cur = obj;
  for (const seg of dotted.split('.')) {
    if (cur == null || typeof cur !== 'object' || Array.isArray(cur)) return undefined;
    if (!Object.prototype.hasOwnProperty.call(cur, seg)) return undefined;
    cur = cur[seg];
  }
  return cur;
}

/** 文案必须是 string；对象 / 数组 / undefined 一律算缺失 */
function resolve(obj, key) {
  const v = lookup(obj, key);
  return typeof v === 'string' && v.trim() !== '' ? v : undefined;
}

let bad = 0;
let total = 0;

for (const file of targets) {
  const text = fs.readFileSync(file, 'utf8');
  // 只抓静态 t('a.b.c')；跳过模板串 t(`...`) 与变量拼接 t('a.b.' + x)。
  // 拼接的 key 静态分析永远查不到（invoice.reimburseStatus_ + status 就是这种），
  // 报出来是纯噪声，久而久之没人看输出。
  // 注意这里必须写 (?!\s*\+) 而不是 \s*(?!\+)：
  // 后者会被正则回溯击穿 —— \s* 可以退回 0 个空格，于是前瞻看到的是空格而不是 +，
  // 拼接写法的 key 照样会被误报。
  const re = /(?<![A-Za-z0-9_])t\(\s*'([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_-]+)+)'(?!\s*\+)/g;
  const seen = new Set();
  for (const m of text.matchAll(re)) {
    const key = m[1];
    if (seen.has(key)) continue;
    seen.add(key);
    total++;
    const z = resolve(zh, key);
    const e = resolve(en, key);
    if (!z && !e) {
      console.log(`  MISSING BOTH  ${file}  ${key}`);
      bad++;
    } else if (!z) {
      console.log(`  MISSING zh    ${file}  ${key}`);
      bad++;
    } else if (!e) {
      console.log(`  MISSING en    ${file}  ${key}`);
      bad++;
    }
  }
}

console.log(`checked ${total} static keys across ${targets.length} file(s); problems: ${bad}`);
process.exit(bad === 0 ? 0 : 1);