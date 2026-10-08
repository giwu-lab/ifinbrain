/**
 * Worker 路径集中配置（仅 Monaco）。
 *
 * 为什么是 dev-only：
 *  - Vite 6.4 解析不了 `?worker` bare-specifier（参见 AGENTS.md §10.1），
 *    `?url` 在 production build 时 Rollup 不会处理 worker 内部相对 import。
 *  - vite-plugin-monaco-editor 走 CDN 路线，不适合本地部署。
 *  - 项目本身 dev-only（AGENTS.md §10.2），prod build 不需要 ——
 *    下面的 `/node_modules/...` 根相对路径只在 dev server 下成立。
 *  - 档案库预览由 @file-viewer/preset-office 接管，Worker/WASM 资源
 *    由 vite plugin 的 copyAssets 自动落到 public/vendor/，不走这里。
 *
 * 路径规则：
 *  - 默认值 = **本模块所在 origin + 根相对 `/node_modules/...`**。
 *    Vite dev 会把项目根（含 node_modules）下的文件直接按根相对路径吐出去，
 *    所以不需要知道项目装在哪台机器上，也不需要把绝对路径硬编码进来。
 *    换成 LAN IP 访问（http://192.168.x.x:5173）同样成立，因为 origin 是自适应的。
 *
 *  - **不要在这里硬编码绝对路径**（踩过的坑）：曾经写死
 *    `/home/coder/workspace/ifinbrain/frontend` —— 那是某台 Linux 机器的目录。
 *    换到 Windows 上时该路径不存在，Vite 会走 SPA 兜底返回 index.html，
 *    于是 worker 请求拿到的是 `Content-Type: text/html`，浏览器按 HTML spec
 *    拒绝执行，报出完全看不出真相的：
 *      Failed to load module script: non-JavaScript MIME type of "text/html"
 *    实际后果是 Monaco 降级到主线程跑 —— 编辑器还能用，但**没有补全和诊断**。
 *
 *  - 仍可用 Vite 环境变量覆盖单个 worker（改完要重启 vite）：
 *      VITE_MONACO_WORKER_URL=/@fs/D:/path/to/frontend/node_modules/...
 */

/** import.meta.url 形如 http://localhost:5173/src/utils/workers.ts，取其 origin */
const ORIGIN = new URL(import.meta.url).origin;

function workerPath(pkgPath: string, envVar?: string): string {
  if (envVar && import.meta.env[envVar]) return import.meta.env[envVar] as string;
  return `${ORIGIN}/node_modules/${pkgPath}`;
}

/** Monaco editor base worker —— 用于 query/Detail.vue */
export const MONACO_WORKER_URL: string = workerPath(
  'monaco-editor/esm/vs/editor/editor.worker.js',
  'VITE_MONACO_WORKER_URL',
);

/** Monaco JSON language worker */
export const MONACO_JSON_WORKER_URL: string = workerPath(
  'monaco-editor/esm/vs/language/json/json.worker.js',
  'VITE_MONACO_JSON_WORKER_URL',
);

/** Monaco CSS/SCSS/LESS worker */
export const MONACO_CSS_WORKER_URL: string = workerPath(
  'monaco-editor/esm/vs/language/css/css.worker.js',
  'VITE_MONACO_CSS_WORKER_URL',
);

/** Monaco HTML worker */
export const MONACO_HTML_WORKER_URL: string = workerPath(
  'monaco-editor/esm/vs/language/html/html.worker.js',
  'VITE_MONACO_HTML_WORKER_URL',
);

/** Monaco TS/JS language worker */
export const MONACO_TS_WORKER_URL: string = workerPath(
  'monaco-editor/esm/vs/language/typescript/ts.worker.js',
  'VITE_MONACO_TS_WORKER_URL',
);