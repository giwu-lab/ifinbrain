import { http } from './http';
import type { PageResp } from '@/types';

export interface InvoiceItem {
  id: number;
  storageKey: string;
  storageType: string;
  originalFilename: string;
  sizeBytes: number;
  sha256: string;
  invoiceType?: string;
  invoiceNumber?: string;
  issueDate?: string;
  buyerName?: string;
  buyerTaxId?: string;
  sellerName?: string;
  sellerTaxId?: string;
  amountWithoutTax?: number;
  taxAmount?: number;
  totalAmount?: number;
  totalAmountUpper?: string;
  payee?: string;
  reviewer?: string;
  drawer?: string;
  message?: string;
  verificationNo?: string;
  verifier?: string;
  verificationDate?: string;
  uploaderId?: number;
  createdAt: string;
  updatedAt: string;
}

/** 上传+识别的暂存结果（未落库），字段对齐 InvoiceItem 但无 id/storageKey 不变 */
export type PendingInvoice = Omit<InvoiceItem, 'id' | 'verificationNo' | 'verifier' | 'verificationDate' | 'uploaderId' | 'createdAt' | 'updatedAt'> & {
  storageKey: string; // 临时 _tmp/...
  pageCount?: number;
};

/** 分组列表项：同一发票号码合并为一行 */
export interface InvoiceGroupItem {
  invoiceNumber: string;
  ids: number[];
  representativeId: number;
  formats: {
    pdf?: FormatEntry | null;
    xml?: FormatEntry | null;
    ofd?: FormatEntry | null;
  };
  invoiceType?: string;
  issueDate?: string;
  buyerName?: string;
  buyerTaxId?: string;
  sellerName?: string;
  sellerTaxId?: string;
  amountWithoutTax?: number;
  taxAmount?: number;
  totalAmount?: number;
  totalAmountUpper?: string;
  payee?: string;
  reviewer?: string;
  drawer?: string;
  message?: string;
  verificationNo?: string;
  verifier?: string;
  verificationDate?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface FormatEntry {
  recordId: number;
  storageKey: string;
  originalFilename: string;
  sha256: string;
  sizeBytes: number;
  invoiceNumber?: string;
}

export interface CommitBody {
  items: PendingInvoice[];
  verificationNo: string;
  verifier?: string;
  verificationDate?: string;
}

export const invoiceApi = {
  list: (params: {
    page?: number; size?: number; keyword?: string; seller?: string;
    verificationNo?: string; verifier?: string;
    verificationDateFrom?: string; verificationDateTo?: string;
  }) => http.get<unknown, PageResp<InvoiceItem>>('/invoices', { params }),

  listGrouped: (params: {
    page?: number; size?: number; keyword?: string; seller?: string;
    verificationNo?: string; verifier?: string;
    verificationDateFrom?: string; verificationDateTo?: string;
  }) => http.get<unknown, PageResp<InvoiceGroupItem>>('/invoices/groups', { params }),

  detail: (id: number) => http.get<unknown, InvoiceItem>(`/invoices/${id}`),

  uploadAndRecognize: (files: File[]) => {
    const fd = new FormData();
    files.forEach((f) => fd.append('files', f));
    return http.post<unknown, PendingInvoice[]>('/invoices/upload-and-recognize', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
  },

  commit: (body: CommitBody) =>
    http.post<unknown, InvoiceItem[]>('/invoices/commit', body),

  update: (id: number, data: Partial<InvoiceItem>) => http.put(`/invoices/${id}`, data),
  remove: (id: number) => http.delete(`/invoices/${id}`),
  downloadUrl: (key: string) => `/api/files/raw?key=${encodeURIComponent(key)}`,

  /**
   * 预览文件：fetch + bearer + blob + 新标签页打开
   * 返回 { blobUrl, contentType, ext }，调用方负责 URL.revokeObjectURL
   */
  async preview(key: string): Promise<{ blobUrl: string; contentType: string; ext: string }> {
    const token = localStorage.getItem('accessToken') || '';
    const res = await fetch(`/api/files/raw?key=${encodeURIComponent(key)}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const contentType = res.headers.get('Content-Type') || 'application/octet-stream';
    const blob = await res.blob();
    const blobUrl = URL.createObjectURL(blob);
    const dot = key.lastIndexOf('.');
    const ext = dot >= 0 ? key.substring(dot + 1).toLowerCase() : '';
    return { blobUrl, contentType, ext };
  },

  /** 全库查重：按 (invoiceNumber, fileExt) 分组 */
  duplicates: (params: {
    verificationNo?: string; verifier?: string; verificationDate?: string;
  } = {}) => http.get<unknown, {
    key: string; invoiceNumber: string; fileExt: string;
    count: number; records: InvoiceItem[];
  }[]>('/invoices/duplicates', { params }),

  /** 查询本批次发票号中已存在于数据库的记录（不要求 ≥ 2） */
  exists: (invoiceNumbers: string[]) =>
    http.post<unknown, InvoiceItem[]>('/invoices/exists', { invoiceNumbers }),

  /** 触发浏览器下载（带 Bearer），返回文件名 */
  async exportFile(kind: 'csv' | 'xlsx', params: Record<string, string | undefined>) {
    const qs = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => { if (v) qs.set(k, v); });
    const token = localStorage.getItem('accessToken') || '';
    const res = await fetch(`/api/invoices/export/${kind}?${qs.toString()}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    // 从 Content-Disposition 取文件名
    const disp = res.headers.get('Content-Disposition') || '';
    const m = /filename\*?=(?:UTF-8'')?["']?([^"';]+)/i.exec(disp);
    const filename = m ? decodeURIComponent(m[1]) : `invoices.${kind}`;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click(); a.remove();
    URL.revokeObjectURL(url);
    return filename;
  },
};