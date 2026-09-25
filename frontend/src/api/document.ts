import { http } from './http';

/** Spring Data Page JSON 序列化（content/number/size/totalElements/totalPages） */
export interface Page<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

export interface DocumentCategory {
  id: number;
  name: string;
  slug: string;
  icon: string;
  sort: number;
  status: 0 | 1;
  remark?: string;
  createdBy?: number;
  createdAt?: string;
  updatedAt?: string;
  fileCount?: number;
}

export interface DocumentItem {
  id: number;
  categoryId: number;
  name: string;
  storageKey: string;
  storageType: 'local' | 's3';
  originalFilename: string;
  sizeBytes: number;
  sha256?: string;
  mimeType?: string;
  uploaderId?: number;
  uploaderName?: string;
  remark?: string;
  status: 0 | 1;
  createdAt?: string;
  updatedAt?: string;
}

export interface FolderItem {
  name: string;
  /** 类目内相对路径（含子目录），如 "demo/sub1" */
  path: string;
}

export interface BrowseResult {
  currentPath: string;
  categorySlug: string;
  folders: FolderItem[];
  files: DocumentItem[];
}

export const categoryApi = {
  list:   () => http.get<unknown, DocumentCategory[]>('/document-categories'),
  detail: (id: number) => http.get<unknown, DocumentCategory>(`/document-categories/${id}`),
  create: (body: Partial<DocumentCategory>) =>
    http.post<unknown, DocumentCategory>('/document-categories', body),
  update: (id: number, body: Partial<DocumentCategory>) =>
    http.put<unknown, DocumentCategory>(`/document-categories/${id}`, body),
  remove: (id: number) => http.delete(`/document-categories/${id}`),
};

export const documentApi = {
  list:   (categoryId: number, page = 0, size = 20) =>
    http.get<unknown, Page<DocumentItem>>(
      `/documents?categoryId=${categoryId}&page=${page}&size=${size}`,
    ),
  /** 浏览某类目某路径：返回 immediate 子文件夹 + 文件 */
  browse: (categoryId: number, path = '') =>
    http.get<unknown, BrowseResult>(`/document-browse?categoryId=${categoryId}&path=${encodeURIComponent(path)}`),
  detail: (id: number) =>
    http.get<unknown, DocumentItem>(`/documents/${id}`),
  upload: (categoryId: number, file: File, folderPath = '') => {
    const fd = new FormData();
    fd.append('file', file);
    const qs = new URLSearchParams({ categoryId: String(categoryId) });
    if (folderPath) qs.set('path', folderPath);
    return fetch(`/api/documents/upload?${qs.toString()}`, {
      method: 'POST',
      body: fd,
      headers: { Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}` },
    }).then(async (r) => {
      const body = await r.json();
      if (!r.ok || body?.code !== 0) {
        throw new Error(body?.message || `Upload failed: HTTP ${r.status}`);
      }
      return body.data as DocumentItem;
    });
  },
  /** 在类目某目录下创建子文件夹路径（kebab-case 单段或多段，用 / 分隔） */
  createFolder: (categoryId: number, path: string, parentPath = '') =>
    http.post<unknown, { path: string }>('/documents/folders', {
      categoryId, path: parentPath ? `${parentPath}/${path}` : path,
    }),
  rename: (id: number, name: string) =>
    http.put<unknown, DocumentItem>(`/documents/${id}`, { name }),
  remove: (id: number) => http.delete(`/documents/${id}`),
};

/** 预览/下载 URL：download=false = 内嵌预览；true = 触发下载（local 直传 / s3 302 presigned） */
export function fileRawUrl(key: string, download = false) {
  return `/api/files/raw?key=${encodeURIComponent(key)}&download=${download}`;
}
