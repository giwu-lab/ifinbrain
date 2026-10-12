/**
 * 表达式求值结果 —— 移植自 qiyuan `barda-core/src/eval/types/valueAndMsg.tsx`。
 *
 * 与上游的差异：无（纯类型 + 值对象，逻辑可原样复用）。
 */

/** 逐段求值明细。编辑器用它给失败的 `{{}}` 画红波浪线。 */
export interface ExprSegment {
  /** 原始段文本（含 `{{` `}}`） */
  value: string;
  /** 该段求值是否成功 */
  success: boolean;
}

export interface ValueExtra {
  segments?: ExprSegment[];
}

/** 把 Error 拍平成一行可展示的字符串 */
export function getErrorMessage(err: unknown): string {
  if (err instanceof Error) {
    return err.name + ': ' + err.message;
  }
  return 'UnknownError: unknown exception during eval';
}

/**
 * 值 + 错误消息的封装。
 *
 * 设计点：**求值失败不抛异常**，而是把失败信息装进 `msg` 并返回。
 * 表单设计器里一个字段表达式写错，不应该让整张表单白屏 ——
 * 降级成空串 + 一条错误，由调用方决定是显示红波浪线还是静默吞掉。
 */
export class ValueAndMsg<T> {
  /** 值 */
  value: T;
  /** 错误消息；存在即表示求值失败 */
  msg?: string;
  /** 逐段明细 */
  extra?: ValueExtra;

  constructor(value: T, msg?: string, extra?: ValueExtra) {
    this.value = value;
    this.msg = msg;
    this.extra = extra;
  }

  hasError(): boolean {
    return this.msg !== undefined;
  }
}