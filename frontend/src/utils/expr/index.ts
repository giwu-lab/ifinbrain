/**
 * 表达式内核 —— 借鉴自 qiyuan（Barda）的 `barda-core/src/eval`，按本项目
 * 表单场景收窄后重写。
 *
 * 设计定位（与上游的分工）：
 *   - **运行期重算** 交给 Vue 响应式（`computed` / 模板内直接调用）——
 *     Vue 的依赖追踪本身就是 Barda 那套 `dependMap` + `CodeNode.justEval` 的
 *     替代品，且更准。沙箱的 `get` 陷阱会读取 reactive 的 formData，
 *     因此模板里直接 `evalText(f.label, formData)` 就是响应式的。
 *   - **设计期诊断**（未定义字段、引用重命名）保留在本模块的 `deps.ts`，
 *     这是 Vue 做不到的部分。
 *
 * 刻意未移植：Node 依赖图（node/codeNode/recordNode）、Function 模式、
 * JSON 模式及其 `really-relaxed-json` 依赖。理由见各文件头注释。
 */

export { evalDefault, evalText, hasExpression, stripExpressions } from './eval';
export { evalScript, evalFunc, createBlackHole, type SandboxOption } from './sandbox';
export {
  getDynamicStringSegments,
  isDynamicSegment,
} from './segments';
export {
  getIdentifiers,
  getExpressionIdentifiers,
  referencedFieldKeys,
  renameFieldKey,
  renameFieldKeyInAll,
  undefinedFieldRefs,
} from './deps';
export { ValueAndMsg, getErrorMessage, type ExprSegment } from './types';