// frontend/src/utils/pdfWorker.ts
//
// PDF.js worker 单例装载 —— 用 fetch + 剥离 Vite 注入 + Blob URL，绕开 Vite dev server
// 给 worker URL 的注入坑。
//
// 坑：直接 `new Worker('/@fs/.../pdf.worker.min.mjs', { type: 'module' })` 时，
//   Vite 在响应首行注入 `import { injectQuery as __vite__injectQuery } from "/@vite/client";`，
//   worker 上下文 import /@vite/client 失败 → Worker 启动后立刻挂掉 →
//   PDF.js getDocument().promise 永远 hang → canvas 空白。
//
// 解决：
//   1. fetch 拿 worker 字节（注意：fetch 拿到的是注入后的内容，blob 里仍含首行注入）
//   2. 剥掉首行 `import { injectQuery ... } from "/@vite/client";`（固定 1 行）
//   3. URL.createObjectURL(new Blob([cleanSource])) 喂给 Worker（不走 Vite URL，无注入）
//
// 路径来源 utils/workers.ts，可用 VITE_PDFJS_WORKER_URL 覆盖（参见该文件）。
//
// 用法：load() 入口 await ensurePdfWorkerReady() 后再调 getDocument()，
//  保证 GlobalWorkerOptions.workerPort 已就位，PDF.js 走 #initializeFromPort 路径，避开 workerSrc。
import * as pdfjsLib from 'pdfjs-dist';
import { PDFJS_WORKER_URL } from './workers';

// Vite 给 /@fs/ URL 注入的首行 —— 必须剥掉，否则 worker 启动失败
const VITE_INJECT_PREFIX =
  /^\s*import\s*\{\s*injectQuery\s+as\s+__vite__injectQuery\s*\}\s*from\s*["']\/@vite\/client["']\s*;?/;

let workerReadyPromise: Promise<void> | null = null;

export function ensurePdfWorkerReady(): Promise<void> {
  if (workerReadyPromise) return workerReadyPromise;
  workerReadyPromise = (async () => {
    const resp = await fetch(PDFJS_WORKER_URL);
    if (!resp.ok) {
      throw new Error(`PDF worker fetch HTTP ${resp.status}`);
    }
    let source = await resp.text();
    // 剥离 Vite 注入的首行（fetch 拿到的是注入后的内容）
    source = source.replace(VITE_INJECT_PREFIX, '');
    const workerUrl = URL.createObjectURL(
      new Blob([source], { type: 'application/javascript' }),
    );
    pdfjsLib.GlobalWorkerOptions.workerPort = new Worker(workerUrl, {
      type: 'module',
    });
  })();
  // 失败时清空缓存，下一次调用会重试（避免一次性永久卡死）
  workerReadyPromise.catch(() => {
    workerReadyPromise = null;
  });
  return workerReadyPromise;
}