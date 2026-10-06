import { http } from './http';

/**
 * 任务标签。后端只返回 id + name 两个字段：
 * 标签实体是 todo_tag 字典下的条目，字典的 code/label/sort 等管理字段不外泄给 todo 模块。
 * 标签下拉的 value 用的是 **name** 而不是 id（见 views/todo/task/Index.vue 的 el-option）。
 */
export interface TodoTag {
  id: number;
  name: string;
}

export interface TodoTask {
  id: number;
  /** 任务执行人（sys_user.id）。 */
  assigneeId: number;
  /** 执行人展示名（冗余字段）。 */
  assigneeName?: string;
  /** 任务创建者。admin 创建给其他人时与 assignee 不同。 */
  createdBy?: number | null;
  createdByName?: string;
  title: string;
  description?: string;
  priority: 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';
  status: 'PENDING' | 'COMPLETED';
  dueAt?: string | null;
  parentId?: number | null;
  recurrence: 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';
  /** WEEKLY: 1-7 ISO dow；MONTHLY/YEARLY: 1-31（0 = 最后一天，仅 MONTHLY） */
  recurrenceDow?: number | null;
  recurrenceDom?: number | null;
  recurrenceMonth?: number | null;
  completedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
  tagIds?: number[];
  tagNames?: string[];
}

/** 后端 /dashboard 返回：{ items, total }。items 受 limit 限制，total 是真实 PENDING 总数。 */
export interface TodoDashboardResponse {
  items: TodoDashboardItem[];
  /** 当前用户（或 admin 全员）PENDING 任务真实总数（与 items.length 解耦） */
  total: number;
}

export interface TodoDashboardItem {
  id: number;
  title: string;
  priority: TodoTask['priority'];
  dueAt?: string | null;
  recurrence: TodoTask['recurrence'];
  recurrenceDow?: number | null;
  recurrenceDom?: number | null;
  recurrenceMonth?: number | null;
}

export interface TodoParentCandidate {
  id: number;
  title: string;
  priority: TodoTask['priority'];
  dueAt: string;
}

export interface TodoAssigneeOption {
  id: number;
  username: string;
  displayName?: string;
}

export interface TodoTaskCreateReq {
  title: string;
  description?: string;
  priority?: TodoTask['priority'];
  recurrence?: TodoTask['recurrence'];
  recurrenceDow?: number | null;
  recurrenceDom?: number | null;
  recurrenceMonth?: number | null;
  dueAt?: string | null;
  parentId?: number | null;
  tagNames?: string[];
  /** 可空，默认当前用户；admin 或创建者可改。 */
  assigneeId?: number | null;
}

export interface TodoTaskUpdateReq {
  title?: string;
  description?: string;
  priority?: TodoTask['priority'];
  status?: TodoTask['status'];
  recurrence?: TodoTask['recurrence'];
  recurrenceDow?: number | null;
  recurrenceDom?: number | null;
  recurrenceMonth?: number | null;
  dueAt?: string | null;
  parentId?: number | null;
  tagNames?: string[] | null;
  assigneeId?: number | null;
}

export interface TodoAttachment {
  id: number;
  taskId: number;
  storageType: 'local' | 's3';
  originalFilename: string;
  sizeBytes: number;
  sizeLabel: string;
  mimeType?: string;
  uploaderId?: number;
  uploaderName?: string;
  createdAt: string;
}

export const todoTagApi = {
  list: () => http.get<unknown, TodoTag[]>('/todo/tags'),
  upsert: (name: string) => http.post<unknown, number>('/todo/tags/upsert', { name }),
  remove: (id: number) => http.delete<unknown, void>(`/todo/tags/${id}`),
};

export const todoApi = {
  list: (params: { page?: number; size?: number; status?: string; priority?: string; keyword?: string }) =>
    http.get<unknown, { items: TodoTask[]; total: number; page: number; size: number; adminViewAll: boolean }>(
      '/todo/tasks', { params }),
  detail: (id: number) => http.get<unknown, TodoTask>(`/todo/tasks/${id}`),
  create: (data: TodoTaskCreateReq) => http.post<unknown, number>('/todo/tasks', data),
  update: (id: number, data: TodoTaskUpdateReq) => http.put<unknown, void>(`/todo/tasks/${id}`, data),
  remove: (id: number) => http.delete<unknown, void>(`/todo/tasks/${id}`),
  complete: (id: number) => http.post<unknown, number | null>(`/todo/tasks/${id}/complete`),
  parentCandidates: (excludeId?: number) =>
    http.get<unknown, TodoParentCandidate[]>('/todo/tasks/parent-candidates', { params: excludeId ? { excludeId } : {} }),
  assignees: () => http.get<unknown, TodoAssigneeOption[]>('/todo/tasks/assignees'),
  dashboard: (limit = 10) =>
    http.get<unknown, TodoDashboardResponse>('/todo/tasks/dashboard', { params: { limit } }),
};

export const todoAttachmentApi = {
  list: (taskId: number) =>
    http.get<unknown, TodoAttachment[]>(`/todo/tasks/${taskId}/attachments`),
  upload: (taskId: number, file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return fetch(`/api/todo/tasks/${taskId}/attachments`, {
      method: 'POST',
      body: fd,
      headers: { Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}` },
    }).then(async (r) => {
      const body = await r.json();
      if (!r.ok || body?.code !== 0) {
        throw new Error(body?.message || `HTTP ${r.status}`);
      }
      return body.data as TodoAttachment;
    });
  },
  remove: (id: number) => http.delete<unknown, void>(`/todo/attachments/${id}`),
  rawUrl: (id: number, inline = false) =>
    `/api/todo/attachments/${id}/raw?inline=${inline ? 'true' : 'false'}&_t=${Date.now()}`,
  /** fetch + bearer + blob + 触发下载（绕开 axios 已知 bug）。 */
  async download(id: number, filename: string) {
    const token = localStorage.getItem('accessToken') || '';
    const res = await fetch(`/api/todo/attachments/${id}/raw?inline=false`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 0);
  },
  /** 内嵌预览（image / pdf 等） → 返回 blob URL，调用方负责 revokeObjectURL。 */
  async preview(id: number): Promise<{ blobUrl: string; contentType: string; ext: string }> {
    const token = localStorage.getItem('accessToken') || '';
    const res = await fetch(`/api/todo/attachments/${id}/raw?inline=true`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const contentType = res.headers.get('Content-Type') || 'application/octet-stream';
    const blob = await res.blob();
    const blobUrl = URL.createObjectURL(blob);
    const dot = blob.type.indexOf('/') >= 0 ? -1 : -1; // 用 Content-Type 推断 ext
    const ext = (() => {
      if (contentType.startsWith('image/')) return contentType.substring(6);
      if (contentType === 'application/pdf') return 'pdf';
      if (contentType.startsWith('video/')) return contentType.substring(6);
      return '';
    })();
    return { blobUrl, contentType, ext };
  },
};