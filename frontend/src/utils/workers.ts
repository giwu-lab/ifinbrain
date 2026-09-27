/**
 * Worker 路径集中配置（仅 Monaco）。
 *
 * 为什么是 dev-only：
 *  - Vite 6.4 解析不了 `?worker` bare-specifier（参见 AGENTS.md §10.1），
 *    `?url` 在 production build 时 Rollup 不会处理 worker 内部相对 import。
 *  - vite-plugin-monaco-editor 走 CDN 路线，不适合本地部署。
 *  - 项目本身 dev-only（AGENTS.md §10.2），prod build 不需要。
 *  - 档案库预览由 @file-viewer/preset-office 接管，Worker/WASM 资源
 *    由 vite plugin 的 copyAssets 自动落到 public/vendor/，不走这里。
 *
 * 路径规则：
 *  - 默认值 `/@fs/<project>/node_modules/<pkg>/<file>` 适配 Vite 6 的 filesystem 路由。
 *  - 可通过 Vite 环境变量 `VITE_PROJECT_ROOT` 覆盖项目根；分别通过
 *    `VITE_MONACO_WORKER_URL` 等覆盖单个 worker。
 *  - 换机器或换项目路径时无需改代码。
 *
 * 例：在 frontend/.env.local 写
 *   VITE_PROJECT_ROOT=/home/another/workspace/ifinbrain/frontend
 *   VITE_MONACO_WORKER_URL=/@fs/another-path/frontend/node_modules/monaco-editor/esm/vs/editor/editor.worker.js
 */

const projectRoot = import.meta.env.VITE_PROJECT_ROOT
  || '/home/coder/workspace/ifinbrain/frontend';

function workerPath(pkgPath: string, envVar?: string): string {
  if (envVar && import.meta.env[envVar]) return import.meta.env[envVar] as string;
  return `/@fs${projectRoot}/node_modules/${pkgPath}`;
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