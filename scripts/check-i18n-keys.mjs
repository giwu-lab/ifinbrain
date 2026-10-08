// i18n key 覆盖检查：把 locale 文件当对象解析，逐个 key 精确比对
// 用法：node scripts/check-i18n-keys.mjs <vue文件...>
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve('frontend');
function loadLocale(file) {
  let raw = fs.readFileSync(path.join(root, 'src/i18n/locales', file), 'utf8');
  if (raw.charCodeAt(0) === 0xfeff) raw = raw.slice(1);   // 去 BOM
  // locale 文件顶部有块注释，`export default` 不在首行 —— 替换首次出现即可
  const body = raw.replace(/export\s+default\s*/, 'return ');
  // eslint-disable-next-line no-new-func
  return new Function(body)();
}

const zh = loadLocale('zh-CN.ts');
const en = loadLocale('en-US.ts');

/*
 * vue-i18n 的 message compiler 把 {{ }} 当成它自己的插值占位符。
 * 文案里若出现裸的 {{name}}（想表达 SQL 占位符语法这类技术写法），
 * 编译时会抛 "Not allowed nest placeholder" —— 而且是**渲染期**才抛：
 * 消息编译是懒的，只有真正 t() 到那个 key 才炸。
 *
 * 后果非常隐蔽：整棵组件树的 render function 在那一刻中断，
 * Vue 打出 "Unhandled error during execution of render function" 就把这次更新丢了，
 * 界面表现为「点了没反应 / 状态变了但 DOM 不动」，而不是一条像样的报错。
 * （本项目就被 `重命名参数（SQL 中的 {{name}} 引用会同步改写）` 咬过一次，
 *   连带把查询编辑器右侧的元数据/输入参数 tab 点死了。）
 *
 * 正确写法：用 vue-i18n 的字面量语法 {'...'} 把大括号包住，渲染结果仍是 {{name}}。
 */
const BRACE_FILES = ['zh-CN.ts', 'en-US.ts'];
const bareBraces = [];
for (const file of BRACE_FILES) {
  const raw = fs.readFileSync(path.join(root, 'src/i18n/locales', file), 'utf8');
  raw.split(/\r?\n/).forEach((line, i) => {
    // 去掉 {'...'} 字面量（含 TS 转义的 \'...\'），剩下的裸 {{ 就是地雷。
    // 形态：{ \' {{ name }} \' }  —— 两头各一层字面量括号，中间是 {{...}}
    const stripped = line.replace(/\{\\?'\{\{[^}]+\}\}\\?'\}/g, 'LITERAL');
    if (/\{\{/.test(stripped) && /^\s*[A-Za-z0-9_]+:\s*['"`]/.test(line)) {
      bareBraces.push(`${file}:${i + 1}  ${line.trim()}`);
    }
  });
}

function has(obj, dotted) {
  let cur = obj;
  for (const seg of dotted.split('.')) {
    if (cur == null || typeof cur !== 'object' || !(seg in cur)) return false;
    cur = cur[seg];
  }
  return cur !== undefined && cur !== null && String(cur).trim() !== '';
}

const files = process.argv.slice(2);
const used = new Set();
for (const f of files) {
  const text = fs.readFileSync(path.join(root, f), 'utf8');
  for (const m of text.matchAll(/\bt\(\s*'([A-Za-z0-9_.]+)'/g)) used.add(m[1]);
}

const missingZh = [];
const missingEn = [];
const literalZh = [];
for (const k of [...used].sort()) {
  if (!has(zh, k)) missingZh.push(k);
  if (!has(en, k)) missingEn.push(k);
  // zh 值等于 key 本身 = 没翻译 / 抄错了
  const v = k.split('.').reduce((o, s) => (o == null ? o : o[s]), zh);
  if (typeof v === 'string' && v === k) literalZh.push(k);
}

console.log(`检查 ${files.length} 个文件，引用 ${used.size} 个 key`);
console.log(`zh-CN 缺失: ${missingZh.length}`);
missingZh.forEach((k) => console.log('   zh  ' + k));
console.log(`en-US 缺失: ${missingEn.length}`);
missingEn.forEach((k) => console.log('   en  ' + k));
console.log(`zh 值等于 key（未翻译）: ${literalZh.length}`);
literalZh.forEach((k) => console.log('   lit ' + k));
console.log('文案里裸露的 {{ }}（vue-i18n 会在渲染期抛错）: ' + bareBraces.length);
bareBraces.forEach((l) => console.log(`   {{  ${l}\n       → 改用 vue-i18n 字面量语法 {'...'} 把大括号包起来`));
process.exit(
  missingZh.length + missingEn.length + literalZh.length + bareBraces.length ? 1 : 0
);