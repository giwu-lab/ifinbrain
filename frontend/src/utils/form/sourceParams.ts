/**
 * 查询参数绑定的规则层。
 *
 * 背景：查询参数（params）可以填字面量，也可以绑某个表单字段（`{{ $form.xxx }}`）。
 * 运行期由渲染层求值后再发请求；但**设计期没有表单数据**，参数里的表达式求不出值。
 *
 * 所以设计期调 `/table-columns` 同步列时，必须先把绑定了字段的参数摘掉 ——
 * 否则后端会把 `{{ $form.x }}` 当字面量做类型校验，number 型参数直接报
 * 「参数 minId 无法解析为 number」，用户一旦把参数绑到字段就再也同步不了列。
 */
import { hasExpression } from '../expr';

export interface DesignTimeParams {
  /** 能安全发给后端的参数（纯字面量） */
  send: Record<string, string>;
  /** 被摘掉的参数名 —— 失败时要告诉用户为什么 */
  skipped: string[];
}

/** 把参数拆成「可发的字面量」与「绑了字段、设计期求不出值的」 */
export function designTimeParams(params: Record<string, string> | undefined | null): DesignTimeParams {
  const send: Record<string, string> = {};
  const skipped: string[] = [];
  for (const [k, v] of Object.entries(params ?? {})) {
    if (v === undefined || v === null) continue;
    if (hasExpression(v)) skipped.push(k);
    else send[k] = v;
  }
  return { send, skipped };
}
