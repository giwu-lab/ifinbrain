import { http } from './http';
import type { AxiosRequestConfig } from 'axios';

/** 传给 http 拦截器的内部标记：出错时不弹 toast，由调用方自行渲染。 */
type SilentConfig = AxiosRequestConfig & { _silent?: boolean };

/** 一条检索命中的分块。documentId 用于溯源回跳到原档案。 */
export interface RagCitation {
  documentId: number;
  filename: string | null;
  chunkIndex: number;
  /** cosine 距离，越小越相似 */
  distance: number;
  /** ≤160 字的分块摘要 */
  snippet: string;
}

export interface RagAnswer {
  answer: string;
  citations: RagCitation[];
  elapsedMs: number;
}

/** 单档案入库结果。indexed=false 时看 note（扫描件无文本层 / OFD 不支持等）。 */
export interface RagIngestResult {
  documentId: number;
  filename: string;
  indexed: boolean;
  chunkCount: number;
  note: string | null;
}

/** 整库重建结果。单文件失败不阻断整库。 */
export interface RagIngestSummary {
  libraryId: number;
  slug: string;
  total: number;
  indexed: number;
  skipped: number;
  failed: number;
}

export interface RagStats {
  totalChunks: number;
}

/**
 * 档案库问答（RAG）。
 *
 * 注意：后端三个 controller 都带 `@ConditionalOnProperty(ifinbrain.rag.enabled=true)`，
 * RAG 关闭时整组端点不存在，调用返回 404。所以这里不提供「是否启用」的静态判断 ——
 * 由调用方用 {@link ragApi.stats} 探测，探测失败即视为未启用（见 RagChatDrawer）。
 */
export const ragApi = {
  /**
   * 提问。问题为空 / 超过 500 字由后端 @Valid 拦截。
   * _silent：失败原因由问答抽屉内联展示，不重复弹 toast。
   */
  ask: (question: string) =>
    http.post<unknown, RagAnswer>('/rag/ask', { question }, { _silent: true } as SilentConfig),

  /** 单档案重新入库（覆盖式：先删后插，可重复调用）。 */
  indexDocument: (id: number) =>
    http.post<unknown, RagIngestResult>(`/rag/documents/${id}/index`),

  /** 整库重建索引。同步执行，大库会慢。 */
  reindexLibrary: (id: number) =>
    http.post<unknown, RagIngestSummary>(`/rag/libraries/${id}/reindex`),

  /** 当前已索引的分块总数。 */
  stats: () => http.get<unknown, RagStats>('/rag/stats'),
};
