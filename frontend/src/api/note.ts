import { http } from './http';
import type { ArchiveLibrary } from './document';
import type { DocumentItem } from './document';

/** Spring Data Page JSON 序列化 */
export interface NotePage<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

export type NoteScope = 'PERSONAL' | 'TEAM' | 'LIBRARY' | 'ORG';
export type NoteGrant = 'OWNER' | 'WRITE' | 'READ';

/** 列表 tab 的 scope 取值（不是 NoteScope —— 那说的是笔记自身的可见性档位） */
export type NoteListScope = 'all' | 'mine' | 'shared' | 'team' | 'library' | 'archived';

export interface NoteItem {
  id: number;
  title: string;
  /** 仅详情接口返回 */
  content?: string;
  /** 列表接口返回的纯文本摘要 */
  excerpt?: string;
  scopeType: NoteScope;
  scopeDeptId?: number;
  scopeDeptName?: string;
  categoryId?: number;
  categoryName?: string;
  tags: string[];
  pinned: boolean;
  /** 1=活跃 0=已归档 */
  status: 0 | 1;
  relatedDocumentId?: number;
  relatedDocumentName?: string;
  ownerId: number;
  ownerName?: string;
  lastEditorName?: string;
  version: number;
  createdAt?: string;
  updatedAt?: string;
  canEdit: boolean;
  canShare: boolean;
  sharedWithMe: boolean;
  myGrant?: NoteGrant;
  owner: boolean;
}

export interface NoteCreatePayload {
  title: string;
  content?: string;
  scopeType?: NoteScope;
  scopeDeptId?: number;
  categoryId?: number;
  tags?: string[];
  relatedDocumentId?: number;
}

/** 更新必带 version —— 服务端做乐观锁，不匹配返回 409 */
export interface NoteUpdatePayload extends Omit<NoteCreatePayload, 'content'> {
  content?: string;
  version: number;
}

export interface NoteShareItem {
  userId: number;
  username?: string;
  displayName?: string;
  deptId?: number;
  deptName?: string;
  grantType: 'READ' | 'WRITE';
  createdAt?: string;
}

export interface NoteCandidate {
  id: number;
  username: string;
  displayName?: string;
  deptId?: number;
  deptName?: string;
}

export interface NoteRevisionItem {
  revision: number;
  title: string;
  content?: string;
  editorName?: string;
  createdAt?: string;
}

export interface NoteStats {
  mine: number;
  shared: number;
  team: number;
  archived: number;
}

export interface NoteListQuery {
  page?: number;
  size?: number;
  scope?: NoteListScope;
  keyword?: string;
  tag?: string;
  categoryId?: number;
  onlyArchived?: boolean;
  onlyPinned?: boolean;
}

export const noteApi = {
  list: (q: NoteListQuery = {}) => {
    const qs = new URLSearchParams();
    if (q.page) qs.set('page', String(q.page));
    qs.set('size', String(q.size ?? 20));
    if (q.scope && q.scope !== 'all') qs.set('scope', q.scope);
    if (q.keyword) qs.set('keyword', q.keyword);
    if (q.tag) qs.set('tag', q.tag);
    if (q.categoryId) qs.set('categoryId', String(q.categoryId));
    if (q.onlyArchived) qs.set('onlyArchived', 'true');
    if (q.onlyPinned) qs.set('onlyPinned', 'true');
    return http.get<unknown, NotePage<NoteItem>>(`/notes?${qs.toString()}`);
  },
  stats: () => http.get<unknown, NoteStats>('/notes/stats'),
  detail: (id: number) => http.get<unknown, NoteItem>(`/notes/${id}`),
  create: (body: NoteCreatePayload) => http.post<unknown, NoteItem>('/notes', body),
  update: (id: number, body: NoteUpdatePayload) =>
    http.put<unknown, NoteItem>(`/notes/${id}`, body),
  remove: (id: number) => http.delete(`/notes/${id}`),
  pin: (id: number, pinned: boolean) => http.put<unknown, NoteItem>(`/notes/${id}/pin`, { pinned }),
  archive: (id: number, archived: boolean) =>
    http.put<unknown, NoteItem>(`/notes/${id}/archive`, { archived }),

  /** 共享对话框的候选用户 / 可关联的档案库 */
  candidates: () => http.get<unknown, NoteCandidate[]>('/notes/candidates'),
  libraries: () => http.get<unknown, ArchiveLibrary[]>('/notes/libraries'),

  shares: (id: number) => http.get<unknown, NoteShareItem[]>(`/notes/${id}/shares`),
  replaceShares: (id: number, items: { userId: number; grantType: 'READ' | 'WRITE' }[]) =>
    http.put<unknown, NoteShareItem[]>(`/notes/${id}/shares`, { items }),

  revisions: (id: number) => http.get<unknown, NoteRevisionItem[]>(`/notes/${id}/revisions`),
  revision: (id: number, revision: number) =>
    http.get<unknown, NoteRevisionItem>(`/notes/${id}/revisions/${revision}`),
  restore: (id: number, revision: number) =>
    http.post<unknown, NoteItem>(`/notes/${id}/revisions/${revision}/restore`),

  /** 发布为档案库里的 .md 文件，返回新建的档案行 */
  publish: (id: number, body: { categoryId?: number; folderPath?: string }) =>
    http.post<unknown, DocumentItem>(`/notes/${id}/publish`, body),
};