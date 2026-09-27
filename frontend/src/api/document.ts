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

export interface ArchiveLibrary {
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
  /** 档案库权限码前缀，如 documents:cw —— 4 个 verb 由后端自动补全 */
  permissionCode?: string;
}

/** 档案库授权：4 个权限 verb 各绑定到哪些角色 id */
export interface ArchivePermGrants {
  view: number[];
  upload: number[];
  update: number[];
  delete: number[];
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
  /** 档案库内相对路径（含子目录），如 "demo/sub1" */
  path: string;
}

export interface BrowseResult {
  currentPath: string;
  categorySlug: string;
  folders: FolderItem[];
  files: DocumentItem[];
}

/** 档案库（原「类目」）CRUD + 授权。端点：/api/archives */
export const archiveApi = {
  list:   () => http.get<unknown, ArchiveLibrary[]>('/archives'),
  detail: (id: number) => http.get<unknown, ArchiveLibrary>(`/archives/${id}`),
  create: (body: Partial<ArchiveLibrary>) =>
    http.post<unknown, ArchiveLibrary>('/archives', body),
  update: (id: number, body: Partial<ArchiveLibrary>) =>
    http.put<unknown, ArchiveLibrary>(`/archives/${id}`, body),
  remove: (id: number) => http.delete(`/archives/${id}`),
  /** 拉取该档案库 4 个权限码当前绑定的角色 id（供授权对话框预填） */
  permissions: (id: number) =>
    http.get<unknown, ArchivePermGrants>(`/archives/${id}/permissions`),
  /** 全量替换授权：admin 强制保留，后端负责 */
  updatePerms: (id: number, grants: ArchivePermGrants) =>
    http.put(`/archives/${id}/permissions`, grants),
};

export const documentApi = {
  list:   (categoryId: number, page = 0, size = 20) =>
    http.get<unknown, Page<DocumentItem>>(
      `/documents?categoryId=${categoryId}&page=${page}&size=${size}`,
    ),
  /** 浏览某档案库某路径：返回 immediate 子文件夹 + 文件 */
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
  /** 在档案库某目录下创建子文件夹路径（kebab-case 单段或多段，用 / 分隔） */
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
