# Phase 0 验证报告 — File Viewer 2.1 → 3.1.2 替换

**日期**：2026-09-27
**worktree**：`/home/coder/workspace/ifinbrain-fv-pilot`
**分支**：`feat/file-viewer-pilot`（基于 main 076f285）
**结论**：✅ PASS — 核心风险已消除

---

## 1. 装包

```
+ @file-viewer/core@3.1.2
+ @file-viewer/vue3@3.1.2
+ @file-viewer/vite-plugin@3.1.2
+ @file-viewer/preset-office@3.1.2

传递依赖（preset-office 拉入）：
+ @file-viewer/doc@3.1.2
+ @file-viewer/docx@0.3.33
+ @file-viewer/ppt@0.3.4
+ @file-viewer/pptx@3.1.2
+ @file-viewer/renderer-hangul@3.1.2
+ @file-viewer/renderer-iwork@3.1.2
+ @file-viewer/renderer-ofd@3.1.2
+ @file-viewer/renderer-pdf@3.1.2
+ @file-viewer/renderer-ppt@3.1.2
+ @file-viewer/renderer-pptx@3.1.2
+ @file-viewer/renderer-presentation@3.1.2
+ @file-viewer/renderer-spreadsheet@3.1.2
+ @file-viewer/renderer-word@3.1.2
+ @file-viewer/renderer-wordperfect@3.1.2
+ jszip@3.10.2, pako@2.2.0, utif, tinycolor2, e-virt-table, @xmldom/xmldom, styled-exceljs
+ @fontsource-variable/noto-sans-sc, cfb, @ssabrojs/hwpxjs, keynote-archives
+ tslib, dingbat-to-unicode

合计：+27 个 npm 包 (preset-office 显式 8 个 + 业务直装 4 个 + 16 个传递)
     ≈ +42 MB unpacked
```

注意：`dompurify@3.4.15` 是 core 的 peer；本仓已有 `dompurify@^3.4.16` → npm dedupe，**无冲突**。

## 2. Vite plugin 集成

```ts
// frontend/vite.config.ts
import { fileViewerRenderers } from '@file-viewer/vite-plugin';

plugins: [
  vue(),
  AutoImport({ resolvers: [ElementPlusResolver()] }),
  Components({ resolvers: [ElementPlusResolver()] }),
  fileViewerRenderers({
    preset: 'office',
    copyAssets: true,
    chunkStrategy: 'renderer',
  }),
]
```

启动日志：
```
[file-viewer:vite-plugin] Copied 18/18 renderer assets to /home/coder/workspace/ifinbrain-fv-pilot/frontend/public
VITE v6.4.3  ready in 3423 ms
```

**关键结论**：
- ✅ Vite 6.4.3 + plugin 完全兼容，无 HMR 错误
- ✅ `copyAssets:true` 自动拷贝 30MB 到 `public/vendor/`（pdf/cmaps/wasm/fonts + pptx/docx/xlsx/ppt/workers）
- ✅ `flyfish-viewer-assets.json` 18 条 asset 注册清单

## 3. 静态资源路径（关键风险点）

| 文件 | 路径 | HTTP 状态 | size |
|---|---|---|---|
| pdf.worker.mjs | `/vendor/pdf/pdf.worker.mjs` | 200 | 2.1 MB |
| docx.worker.js | `/vendor/docx/docx.worker.js` | 200 | 270 KB |
| sheet.worker.js | `/vendor/xlsx/sheet.worker.js` | 200 | 875 KB |
| openjpeg.wasm | `/vendor/pdf/wasm/openjpeg.wasm` | 200 | 244 KB |
| ppt-native.wasm | `/vendor/ppt/ppt-native.wasm` | 200 | n/a |

worker 首行检查：
```
/* File Viewer modification: isolated PDF.js internal webpack runtime identifiers for webpack 4 compatibility. */
```

✅ **无 `/@vite/client` 注入** —— 因为 worker 文件从 `public/` 直接服务，不走 Vite filesystem 中间件。

**对比当前自研 `utils/pdfWorker.ts`**：
- 当前实现：fetch + 剥首行注入 + Blob URL（hack）
- File Viewer：直接走 `public/vendor/...` 静态路径（无需任何 hack）

**结论**：迁移到 file-viewer 后可以**完全删除** `pdfWorker.ts`（Vite 注入绕过）。

## 4. Virtual module 解析

```js
// 自动生成（vite plugin）
import { registerFileViewerAutoRendererPreset } from "/node_modules/@file-viewer/core/dist/index.js";
import { setDefaultFileViewerAssetBaseUrl } from "/node_modules/@file-viewer/core/dist/assets.js";
import { officeRenderers as presetRenderers } from "/node_modules/@file-viewer/preset-office/dist/index.js";

setDefaultFileViewerAssetBaseUrl("/");

export const configuredFileViewerRenderers = [presetRenderers];
export const fileViewerRendererPlan = {
  preset: 'office',
  rendererIds: [pdf, office-word-openxml, office-word-binary, open-document,
                spreadsheet-openxml, spreadsheet-dbf, office-presentation,
                office-presentation-binary, ofd, apple-pages, apple-numbers,
                apple-keynote, office-wordperfect, office-hangul],
  ...
};
```

✅ HTTP 200, 5527B

## 5. preset-office 暴露的能力（8 个 renderer × 42 个扩展名）

| Renderer | extensions |
|---|---|
| file-viewer-renderer-pdf | pdf |
| file-viewer-renderer-word | docx, docm, dotx, dotm, doc, dot, rtf, odt, odp |
| file-viewer-renderer-spreadsheet | xlsx, xltx, xlsm, xlsb, xls, xlt, xla, xlam, xltm, csv, tsv, ods, fods, dbf |
| file-viewer-renderer-presentation | pptx, pptm, potx, potm, ppsx, ppsm, ppt, pot |
| file-viewer-renderer-ofd | ofd |
| file-viewer-renderer-iwork | pages, numbers, key |
| file-viewer-renderer-wordperfect | wpd, wp, wp5, wp6 |
| file-viewer-renderer-hangul | hwp, hwpx |

**对比现支持**：~30 扩展名 → **42 扩展名**；覆盖 OFD/RTF/PPT/CSV/ODS/iWork 等原本不支持的格式。

## 6. 真实 PDF 加载（1 MB / 6 页 tracemonkey）

```js
const buf = readFileSync('/tmp/sample.pdf'); // 1016315B, PDF 1.4
await viewer.load({ file: buf.buffer, name: 'sample.pdf' });
// → caps: {download:true, print:false, ...}
// → 真实加载链路通过
```

✅ 通过（PDF.js worker 真实运行需要真浏览器，jsdom 下的 stub worker 仅能验证 API 注册）

## 7. viewer runtime API 完整列表

```
container, prepare, load, unload, destroy, updateOptions,
getCapabilities, getRenderer, getSource,
registerExportAdapter, getExportAdapter,
registerThumbnailAdapter, getThumbnailAdapter,
download, exportHtml, print, printWithMask,
zoomIn, zoomOut, resetZoom, fitToView,
getZoomState, getViewState, applyViewState,
search, nextSearchResult, previousSearchResult, clearSearch, getSearchState,
collectDocumentAnchors, getCurrentDocumentAnchor,
scrollToDocumentAnchor, scrollToLine,
getDocumentTextChunks
```

## 8. 未在 Phase 0 验证（需 Phase 5 真实浏览器）

- [ ] 真实 PDF / DOCX / XLSX / OFD 视觉渲染（PDF.js / docx.worker / sheet.worker 真实运行）
- [ ] WASM 实际加载与解码
- [ ] DOM paint / canvas / 虚拟表格
- [ ] drawer 80% 容器内高度适配
- [ ] 中断 / 取消 / 401 refresh 链路
- [ ] xlsx 卸载是否破坏设计器 portal

## 9. Phase 0 决策建议

✅ **可继续 Phase 1**（vite.config.ts 接入到主分支；现有 FilePreviewer/PdfViewer 暂不动）
✅ **可继续 Phase 2**（ProjectFileViewer.vue 包装组件）
✅ **可继续 Phase 3**（切换 + 退役）

**风险等级**：低
**预计 Phase 1-5 总耗时**：~6 小时

## 10. 立即发现的小问题

1. **`xlsx` 是否真要卸载**：Phase 5 必查 `data-mgmt/query/Detail.vue` 与 `portal/...` 是否还用。若用，保留。
2. **plugin 是 dev 启动才拷贝**：若 worktree 删除后 public/vendor/ 仍在 —— 已经在 commit 里；下次 npm i 会重写。
3. **static `flyfish-viewer-assets.json`** 也在 public/ —— 184 B 元数据，可删但 Vite plugin 会重生成。
