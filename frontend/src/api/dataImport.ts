import { http } from './http';

/**
 * 数据导入：Excel / CSV → 外部数据源表。
 *
 * 四个接口对应向导四步，**只有 commit 会写库**：
 *   preview  上传 + 解析            → 拿到 sessionId + 表头 + 目标列
 *   validate 字段映射 + 预检        → 不写库，只回答「执行会怎样」
 *   commit   真正写入               → 需要 data:import:execute
 *
 * schema 相关类型在这里就地定义（api/datasource.ts 只导出了 Datasource 本体），
 * 字段与后端 DatasourceSchemaDto 一一对应。
 */

/** 单个目标列的元数据 */
export interface ImportColumn {
  name: string;
  /** raw DB type: varchar / int4 / timestamp … */
  dataType: string;
  /** 'string' | 'number' | 'date' | 'datetime' | 'boolean' | 'unknown' */
  type: string;
  nullable?: boolean;
  maxLength?: number | null;
  precision?: number | null;
  scale?: number | null;
  /** 自增 / 生成列：不可映射 */
  generated?: boolean;
}

export interface ImportTable {
  name: string;
  schema: string;
  simpleName: string;
  columns: ImportColumn[];
}

export interface ImportSchema {
  datasourceId: number;
  dbType: string;
  defaultSchema: string;
  tables: ImportTable[];
}

export interface ImportPreview {
  sessionId: string;
  fileName: string;
  fileSize: number;
  /** XLSX | XLS | CSV */
  kind: string;
  sheetNames: string[];
  sheetName?: string | null;
  encoding?: string | null;
  headers: string[];
  sampleRows: string[][];
  totalRows: number;
  /** 文件行数超过上限被截断 */
  truncated: boolean;
  columns: ImportColumn[];
}

export interface ImportMapping {
  sourceHeader: string;
  targetColumn: string | null;
  enabled: boolean;
}

export interface ImportOptions {
  mode?: 'append';
  emptyAsNull?: boolean;
  truncateString?: boolean;
  skipEmptyRow?: boolean;
  batchSize?: number;
}

export interface ImportErrorRow {
  rowNo: number;
  field: string;
  rawValue: string;
  reason: string;
}

export interface ImportValidate {
  sessionId: string;
  totalRows: number;
  wouldInsert: number;
  errorRowCount: number;
  warningCount: number;
  errorRows: ImportErrorRow[];
  /** 目标表当前行数，-1 = 未知（账号无 SELECT 权限） */
  targetRowCount: number;
  valid: boolean;
}

export interface ImportResult {
  logId: number | null;
  totalRows: number;
  insertedRows: number;
  skippedRows: number;
  warningCount: number;
  durationMs: number;
  /** 0=成功 1=部分成功 2=失败 */
  status: number;
  message: string;
  errorRows: ImportErrorRow[];
}

export interface ImportLogItem {
  id: number;
  datasourceId: number;
  datasourceName: string;
  dbType: string;
  targetSchema?: string;
  targetTable: string;
  fileName: string;
  fileSize?: number;
  sheetName?: string;
  importMode: string;
  totalRows?: number;
  insertedRows?: number;
  failedRows?: number;
  skippedRows?: number;
  status: number;
  errorMessage?: string;
  errorRows?: ImportErrorRow[];
  durationMs?: number;
  operatorId?: number;
  operatorName?: string;
  createdAt: string;
}

export interface ImportHistoryPage {
  items: ImportLogItem[];
  total: number;
  page: number;
  size: number;
}

export interface PreviewParams {
  datasourceId: number;
  schema: string;
  table: string;
  sheet?: string;
  hasHeader: boolean;
  encoding: string;
  file: File;
}

export const dataImportApi = {
  /** 上传解析；FormData 里不能手写 Content-Type，交给浏览器带 boundary */
  preview: (p: PreviewParams) => {
    const fd = new FormData();
    fd.append('datasourceId', String(p.datasourceId));
    fd.append('schema', p.schema);
    fd.append('table', p.table);
    if (p.sheet) fd.append('sheet', p.sheet);
    fd.append('hasHeader', String(p.hasHeader));
    fd.append('encoding', p.encoding);
    fd.append('file', p.file);
    return http.post<unknown, ImportPreview>('/datamgmt/imports/preview', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120_000,
      // 解析失败的原因要就地显示在第 2 步，别再弹一次 toast
      _silent: true,
    } as never);
  },

  validate: (body: { sessionId: string; mappings: ImportMapping[]; options: ImportOptions }) =>
    http.post<unknown, ImportValidate>('/datamgmt/imports/validate', body, { timeout: 120_000 }),

  commit: (body: { sessionId: string; mappings: ImportMapping[]; options: ImportOptions }) =>
    http.post<unknown, ImportResult>('/datamgmt/imports/commit', body, { timeout: 300_000 }),

  history: (params?: { page?: number; size?: number; datasourceId?: number }) =>
    http.get<unknown, ImportHistoryPage>('/datamgmt/imports/history', { params }),

  detail: (id: number) => http.get<unknown, ImportLogItem>(`/datamgmt/imports/${id}`),

  remove: (id: number) => http.delete(`/datamgmt/imports/${id}`),

  discardSession: (sessionId: string) => http.delete(`/datamgmt/imports/session/${sessionId}`),

  /** 目标表的真实列结构（给映射界面用；自增列由 generated 标记表达） */
  columns: (datasourceId: number) =>
    http.get<unknown, ImportSchema>(`/datamgmt/datasources/${datasourceId}/schema`),
};