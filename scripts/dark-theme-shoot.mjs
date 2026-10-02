// 暗色主题截图验证脚本
import { chromium } from '/opt/node-22/lib/node_modules/playwright/index.mjs';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const BASE = 'http://localhost:5173';
const OUT = path.resolve('frontend/docs/screenshots');

const PAGES = [
  { path: '/dashboard',             name: 'dashboard' },
  { path: '/invoice',               name: 'invoice-upload' },
  { path: '/invoice/list',          name: 'invoice-list' },
  { path: '/system/user',           name: 'system-user' },
  { path: '/system/role',           name: 'system-role' },
  { path: '/system/menu',           name: 'system-menu' },
  { path: '/system/dict',           name: 'system-dict' },
  { path: '/documents/archive',     name: 'documents-archive' },
  { path: '/todo',                  name: 'todo' },
  { path: '/design/form',           name: 'design-form' },
  { path: '/design/flow',           name: 'design-flow' },
];

async function login(page) {
  await page.goto(BASE + '/login');
  await page.waitForLoadState('networkidle');
  // el-input 渲染成 .el-input > .el-input__wrapper > input.el-input__inner
  const inputs = page.locator('.el-input__inner');
  await inputs.nth(0).fill('admin');
  await inputs.nth(1).fill('Admin@123');
  await page.locator('button:has-text("登录"), button:has-text("Login")').first().click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });
}

async function setTheme(page, theme) {
  await page.evaluate((t) => {
    const raw = localStorage.getItem('ifinbrain.theme.v1');
    const cur = raw ? JSON.parse(raw) : {};
    const next = { ...cur, mode: t };
    localStorage.setItem('ifinbrain.theme.v1', JSON.stringify(next));
  }, theme);
}

async function shot(page, url, name, mode) {
  try {
    await page.goto(BASE + url, { waitUntil: 'networkidle', timeout: 15000 });
    await page.waitForTimeout(500); // 等动画
    const file = `${name}-${mode}.png`;
    await page.screenshot({ path: path.join(OUT, file), fullPage: false });
    console.log(`  ✓ ${file}`);
  } catch (e) {
    console.log(`  ✗ ${url} ${mode}: ${e.message?.slice(0, 80)}`);
  }
}

(async () => {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();

  console.log('登录...');
  await login(page);

  // ====== 亮色主题截图 ======
  console.log('\n=== 亮色主题 ===');
  await setTheme(page, 'light');
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(800);

  for (const p of PAGES) {
    await shot(page, p.path, p.name, 'light');
  }

  // ====== 暗色主题截图 ======
  console.log('\n=== 暗色主题 ===');
  await setTheme(page, 'dark');
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(1000); // dark transition

  for (const p of PAGES) {
    await shot(page, p.path, p.name, 'dark');
  }

  // ====== 额外：登录页亮暗 ======
  console.log('\n=== 登录页 ===');
  await page.evaluate(() => localStorage.removeItem('accessToken'));
  await page.evaluate(() => localStorage.removeItem('refreshToken'));
  await setTheme(page, 'light');
  await shot(page, '/login', 'login', 'light');
  await setTheme(page, 'dark');
  await page.reload({ waitUntil: 'networkidle' });
  await shot(page, '/login', 'login', 'dark');

  await browser.close();
  console.log('\nDone.');
})().catch((e) => { console.error(e); process.exit(1); });