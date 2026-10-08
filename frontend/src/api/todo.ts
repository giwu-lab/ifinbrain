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

  // ---- 按行的操作权限（后端按「角色权限 × 部门归属」逐行计算）----
  //
  // 不能只靠 auth.hasPerm('todo:task:update')：那是角色级权限，
  // 而一个 task_manager 会同时看到本部门任务和跨部门任务，两者可写性不同。
  // 后端对跨部门任务返回 false，前端据此置灰按钮而不是"点了才报 403"。

  /** 当前用户能否编辑本行。 */
  canEdit?: boolean;
  /** 当前用户能否删除本行。 */
  canDelete?: boolean;
  /** 当前用户能否勾选完成本行。 */
  canComplete?: boolean;
  /** 本行是否「可见但只读」= 执行人在别的部门子树内。用于展示提示。 */
  crossDeptReadOnly?: boolean;

  /** 预估投入时长（分钟）。undefined = 未估，前端不渲染时长元素。 */
  estimateMinutes?: number | null;
  /** 四象限归属 Q1/Q2/Q3/Q4，由后端按 priority × dueAt 实时推导，不落库。 */
  quadrant?: string | null;
  /** 直接子任务总数（「子任务 0/2」）。 */
  childTotal?: number;
  /** 已完成的直接子任务数（「子任务 0/2 · 50%」）。 */
  childDone?: number;
  /**
   * 未完成的直接子任务数（= childTotal - childDone）。
   * 大于 0 时后端拒绝把本行置为已完成，前端据此置灰勾选框并提示先做子任务。
   * 与 canComplete=false 分开看：那个还可能是跨部门只读，这个纯粹是「等子任务」。
   */
  pendingChildCount?: number;
}

/** 四象限视图顶部的统计条。 */
export interface TodoStats {
  /** 我的 PENDING 总数。 */
  pending: number;
  /** 今日到期的 PENDING 数。 */
  dueToday: number;
  /** 已逾期的 PENDING 数。 */
  overdue: number;
  completed: number;
  total: number;
  /** 完成率 0~1（分母为 total，即全部任务）。 */
  completionRate: number;
}

/** 四象限的一个格子。四个格子恒定全部返回。 */
export interface TodoQuadrantBucket {
  key: 'Q1' | 'Q2' | 'Q3' | 'Q4';
  count: number;
  tasks: TodoTask[];
}

/** GET /todo/tasks/quadrant 的响应。 */
export interface TodoQuadrantResponse {
  stats: TodoStats;
  quadrants: TodoQuadrantBucket[];
}

/** GET /todo/tasks/today 的响应（逾期 + 今日到期）。 */
export interface TodoTodayResponse {
  items: TodoTask[];
  stats: TodoStats;
}

/** 趋势上的一个点。序列按日期升序，长度恒等于 days（缺失日补 0）。 */
export interface TodoTrendPoint {
  /** yyyy-MM-dd */
  date: string;
  completed: number;
  created: number;
}

/** 象限结构占比。 */
export interface TodoQuadrantShare {
  key: 'Q1' | 'Q2' | 'Q3' | 'Q4';
  count: number;
  /** 该象限内所有非空 estimateMinutes 之和（分钟）。 */
  estimateMinutes: number;
}

/** 行动建议。kind 决定图标与配色。 */
export interface TodoSuggestion {
  /** backlog 积压 / overdue 逾期 / q3 委托失衡 / q4 可删减 / q2 长期未推进 / balanced 健康 */
  kind: 'backlog' | 'overdue' | 'q3' | 'q4' | 'q2' | 'balanced';
  title: string;
  detail: string;
  /** 0~100，用于排序和强调。 */
  severity: number;
}

/** GET /todo/tasks/insight 的响应。 */
export interface TodoInsightResponse {
  /** 实际生效的天数窗口（后端收敛到 7~90）。 */
  days: number;
  /** 完成率 = 窗口内完成数 / 窗口内新建数。 */
  completionRate: number;
  /** 逾期率 = 逾期未完成 / 当前未完成总数。 */
  overdueRate: number;
  /** 平均完成耗时（小时）；无有效样本时为 null，前端显示「—」而不是 0。 */
  avgCycleHours: number | null;
  /** 累计预估投入（小时）。 */
  totalEstimateHours: number;
  /** 有预估时长的任务占比 0~1。 */
  estimatedCoverage: number;
  /** 当前未完成积压（全量口径，不受 days 影响）。 */
  backlog: number;
  createdInRange: number;
  completedInRange: number;
  trend: TodoTrendPoint[];
  quadrants: TodoQuadrantShare[];
  /** 最多 3 条，按 severity 降序。 */
  suggestions: TodoSuggestion[];
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
  /** ISO 8601 带 T：'2026-10-07T18:00:00'。**不要发空格分隔的版本，后端会 400。 */
  dueAt?: string | null;
  /** 预估投入时长（分钟）。undefined = 未估。 */
  estimateMinutes?: number | null;
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
  /** 预估投入时长（分钟）。undefined/null = 不修改。 */
  estimateMinutes?: number | null;
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
  /** 四象限视图（仪表盘「我的任务」）。 */
  quadrant: (limit = 200) =>
    http.get<unknown, TodoQuadrantResponse>('/todo/tasks/quadrant', { params: { limit } }),
  /** 「今天」tab：逾期 + 今日到期。 */
  today: (limit = 50) =>
    http.get<unknown, TodoTodayResponse>('/todo/tasks/today', { params: { limit } }),
  /**
   * 我的 PENDING 任务数 —— 顶栏通知红点。
   *
   * <p>只返回一个数字，不带任务列表：红点每次登录都要拉一次，
   * 走 today() 会白拉 50 行任务实体。形态与 flowTaskApi.pendingCount 对齐。
   */
  pendingCount: () => http.get<unknown, number>('/todo/tasks/pending/count'),
  /**
   * 直接子任务明细（含已完成）—— 父任务卡片展开子任务时懒加载。
   * 只返回直接子级；要展开孙级，对该子任务再调一次本方法即可（天然支持递归）。
   */
  children: (id: number) => http.get<unknown, TodoTask[]>(`/todo/tasks/${id}/children`),
  /** 洞察视图：趋势 + 象限结构 + 建议。days 后端收敛到 7~90。 */
  insight: (days = 7) =>
    http.get<unknown, TodoInsightResponse>('/todo/tasks/insight', { params: { days } }),
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