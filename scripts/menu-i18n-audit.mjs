/**
 * 菜单 i18n 覆盖审计 —— 回归脚本。
 *
 * <p>把后端返回的菜单树按 `resolveMenuName()` 的候选规则逐条试一遍，
 * 报告哪些落到了 `sys_menu.name` 兜底（即界面上显示 DB 里的原文）。
 *
 * <p><b>为什么要它</b>：`menu.*` 的 key 是按 path / component 手写的，
 * 而菜单行是数据（档案库建一行菜单、发布报表建一行菜单…）。
 * 新增/改名之后很容易漏 key，而漏 key 的表现只是「界面显示英文」，
 * 没有任何报错，vue-tsc 也抓不到。
 *
 * <p><b>当前基线：31 个菜单，28 个已覆盖。</b>
 * 剩下 3 个里，2 个是档案库菜单（{@code /documents/cw}、{@code /documents/jsb}），
 * <b>属于预期不覆盖</b>：档案库菜单由 {@code ArchiveLibraryService} 按库逐个生成，
 * 所有库共用同一个 component（{@code documents/list/Index}），
 * 显示名就是管理员填的库名（部门档案库 = 部门名）。
 * 库名是**用户数据**不是界面文案，翻译它是错的 —— 所以刻意不给 key，
 * 让它走 `name` 兜底。新建档案库会多一行 MISS，属正常。
 *
 * <p>第 3 个是发布出去的报表菜单，脚本里单列为 {@code PUB} 行
 * （{@code /^\/bi\/(report|dashboard)\/view\/\d+$/}），<b>不计入基线</b>：
 * 这些菜单 component 恒为 null（V69 起），name 就是管理员填的报表名，
 * 同样是用户数据不是界面文案。多发布几张报表不会动基线。
 *
 * <p>用法（需 backend 在 8080；默认账号在 .env 里）：
 *   node scripts/menu-i18n-audit.mjs
 *   node scripts/menu-i18n-audit.mjs --username admin --password 'xxx'
 *   node scripts/menu-i18n-audit.mjs --expect-uncovered 2   # 允许 2 行未覆盖（档案库）
 */
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

const argv = process.argv.slice(2);
function flag(name, fallback) {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
}
const USERNAME = flag('username', process.env.IFIN_USER || 'admin');
const PASSWORD = flag('password', process.env.IFIN_PASS || 'Admin@123');
const API = flag('api', 'http://localhost:8080');
const EXPECT_UNCOVERED = Number(flag('expect-uncovered', '0'));

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const FE = path.join(ROOT, 'frontend');

// ---- 1. 把两个 locale 打成临时 mjs（TS 直接 import 不了） ----
// 走 esbuild 的 JS API 而不是 node_modules/.bin/esbuild.cmd：
// .cmd 在 Windows 上不能被 execFile 直接 spawn，得靠 shell:true 再多一层引号地狱。
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ifin-i18n-'));
const zhMjs = path.join(tmp, 'zh.mjs');
const enMjs = path.join(tmp, 'en.mjs');
try {
  const esbuild = await import(pathToFileURL(path.join(FE, 'node_modules', 'esbuild', 'lib', 'main.js')).href);
  for (const [src, out] of [['zh-CN.ts', zhMjs], ['en-US.ts', enMjs]]) {
    await esbuild.build({
      entryPoints: [path.join(FE, 'src', 'i18n', 'locales', src)],
      bundle: true,
      format: 'esm',
      outfile: out,
      logLevel: 'error',
    });
  }
  const zh = (await import(pathToFileURL(zhMjs).href)).default;
  const en = (await import(pathToFileURL(enMjs).href)).default;

  // ---- 2. 与 utils/menu.ts 完全一致的三条候选，顺序不能改 ----
  const candidatesOf = (m) => {
    const comp = m.component?.replace(/\//g, '.');
    const p = m.path && m.path !== '/' ? m.path.replace(/^\//, '') : null;
    return [comp ? `menu.${comp}` : null, p ? `menu.${p}` : null, p ? `menu.${p.replace(/\//g, '.')}._` : null].filter(Boolean);
  };
  const lookup = (obj, dotted) => {
    let cur = obj;
    for (const seg of dotted.split('.')) {
      if (cur == null || typeof cur !== 'object' || Array.isArray(cur)) return undefined;
      if (!Object.prototype.hasOwnProperty.call(cur, seg)) return undefined;
      cur = cur[seg];
    }
    return cur;
  };
  const hits = (obj, key) => typeof lookup(obj, key) === 'string';

  // ---- 3. 登录拿菜单树 ----
  const loginRes = await fetch(`${API}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USERNAME, password: PASSWORD }),
  });
  const login = await loginRes.json();
  if (login.code !== 0) {
    console.error(`login failed: code=${login.code} ${login.message}`);
    process.exit(2);
  }
  const meRes = await fetch(`${API}/api/auth/me`, {
    headers: { Authorization: `Bearer ${login.data.accessToken}` },
  });
  const me = await meRes.json();
  if (me.code !== 0) {
    console.error(`auth/me failed: code=${me.code} ${me.message}`);
    process.exit(2);
  }

  const flat = [];
  (function walk(nodes, depth) {
    for (const n of nodes || []) {
      flat.push({ node: n, depth });
      walk(n.children, depth + 1);
    }
  })(me.data.menus, 0);

  // 刻意不覆盖的第二类：发布出去的报表 / 大屏菜单。
  // 这些菜单 component 恒为 null（V69 起），name 就是管理员填的报表名 / 大屏名 ——
  // 和档案库菜单同一形态：显示名是**用户数据**不是界面文案，不该翻译。
  // 单列统计，不计入 miss，这样发布几张报表都不会动基线。
  const PUBLISHED = /^\/bi\/(report|dashboard)\/view\/\d+$/;
  const isPublished = (m) => PUBLISHED.test(m.path || '');

  let miss = 0;
  let published = 0;
  for (const { node: m, depth } of flat) {
    const cands = candidatesOf(m);
    const okZh = cands.some((k) => hits(zh, k));
    const okEn = cands.some((k) => hits(en, k));
    const indent = '  '.repeat(depth);
    if (okZh && okEn) {
      const key = cands.find((k) => hits(zh, k));
      console.log(`  OK    ${indent}${m.path || m.component}  ->  ${key} = ${lookup(zh, key)}`);
    } else if (isPublished(m)) {
      published++;
      console.log(`  PUB   ${indent}${m.path}  ->  DB name = ${JSON.stringify(m.name)} (intentional, not a regression)`);
    } else {
      miss++;
      console.log(`  MISS  ${indent}name=${JSON.stringify(m.name)} path=${JSON.stringify(m.path)} component=${JSON.stringify(m.component)}`);
      for (const k of cands) console.log(`          candidate ${k}  zh=${hits(zh, k)} en=${hits(en, k)}`);
    }
  }

  console.log(`\ntotal menus=${flat.length}  i18n-covered=${flat.length - miss - published}  published-by-name=${published}  falling back to DB name=${miss}`);
  if (miss > EXPECT_UNCOVERED) {
    console.error(`\nFAIL: uncovered ${miss} > expected ${EXPECT_UNCOVERED}`);
    process.exitCode = 1;
  } else {
    console.log(`OK: uncovered ${miss} <= expected ${EXPECT_UNCOVERED}`);
  }
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}