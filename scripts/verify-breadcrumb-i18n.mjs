// 验证面包屑 / tab 标题的 i18n key 解析链
// 复刻 stores/tab.ts deriveNameKey 与各 layout menuName 的取值顺序
import fs from 'node:fs';

const root = 'frontend';
const load = (f) => {
  const raw = fs.readFileSync(`${root}/src/i18n/locales/${f}`, 'utf8');
  return new Function(raw.replace(/^\uFEFF/, '').replace(/export\s+default\s*/, 'return '))();
};
const zh = load('zh-CN.ts');
const en = load('en-US.ts');

const get = (o, dotted) => dotted.split('.').reduce((x, s) => (x == null ? x : x[s]), o);

// 从 router/index.ts 里正则抠出静态路由的 meta.component
const router = fs.readFileSync(`${root}/src/router/index.ts`, 'utf8');
const routes = [...router.matchAll(/path:\s*'([^']+)'[\s\S]{0,320}?meta:\s*\{([^}]*)\}/g)].map((m) => {
  const meta = m[2];
  const comp = meta.match(/component:\s*'([^']+)'/)?.[1] ?? null;
  const name = meta.match(/name:\s*'([^']+)'/)?.[1] ?? null;
  const title = meta.match(/title:\s*'([^']+)'/)?.[1] ?? null;
  return { path: m[1], comp, name, title };
});

console.log('=== 静态路由的面包屑 key 解析结果 ===\n');
let bad = 0;
for (const r of routes) {
  // menuName 的候选顺序：menu.<component> → menu.<path> → menu.<path>._ → meta.name/title
  const comp = r.comp ? `menu.${r.comp.replace(/\//g, '.')}` : null;
  const p = r.path.replace(/^\//, '');
  const candidates = [
    comp,
    r.path !== '/' ? `menu.${p}` : null,
    r.path !== '/' ? `menu.${p.replace(/\//g, '.')}._` : null,
  ].filter(Boolean);

  let resolved = null;
  for (const c of candidates) {
    if (get(zh, c) != null) { resolved = [c, get(zh, c), get(en, c)]; break; }
  }
  const fallbackLabel = r.name ?? r.title;
  const zhFinal = resolved ? resolved[1] : fallbackLabel;
  const enFinal = resolved ? resolved[2] : fallbackLabel;
  const i18n = !!resolved;

  if (!i18n) bad++;
  console.log(`${i18n ? 'OK  ' : 'FALL'} ${r.path}`);
  console.log(`      key      = ${resolved ? resolved[0] : '(无命中)'}`);
  console.log(`      zh / en  = ${zhFinal}  /  ${enFinal}`);
}
console.log(`\n共 ${routes.length} 条静态路由，未命中 i18n 的 ${bad} 条`);