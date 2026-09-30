import { http } from './http';

export type FlowState = 'RUNNING' | 'APPROVED' | 'REJECTED' | 'CANCELED';
export type TaskStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SKIPPED';
export type AssigneeType = 'USER' | 'ROLE' | 'ORG';

export interface FlowTaskItem {
  id: number;
  instanceId: number;
  flowId: number;
  flowCode: string;
  flowName: string;
  businessKey: string;
  instanceState: FlowState;
  nodeId: string;
  nodeLabel: string;
  assigneeType: AssigneeType;
  assigneeId: string;
  assigneeName: string;
  status: TaskStatus;
  comment?: string;
  todoTaskId?: number | null;
  createdAt: string;
  completedAt?: string | null;
  startedBy: string;
  instanceStartedAt: string;
}

export interface FlowInstanceItem {
  id: number;
  flowId: number;
  flowCode: string;
  flowName: string;
  businessKey: string;
  state: FlowState;
  currentNodeId?: string | null;
  currentNodeLabel?: string | null;
  variables?: Record<string, any>;
  startedBy: string;
  startedAt: string;
  finishedAt?: string | null;
  completedTaskCount?: number | null;
  totalTaskCount?: number | null;
  history?: FlowHistoryItem[] | null;
}

export interface FlowHistoryItem {
  id: number;
  instanceId: number;
  nodeId: string;
  nodeLabel?: string | null;
  action: 'SUBMIT' | 'APPROVE' | 'REJECT' | 'DELEGATE' | 'JUMP' | 'COMPLETE';
  operator: string;
  operatorName?: string | null;
  comment?: string | null;
  createdAt: string;
}

export interface StartFlowReq {
  code: string;
  businessKey: string;
  variables?: Record<string, any>;
  startBy?: string;
}

export interface ApproveTaskReq {
  comment?: string;
  completeAll?: boolean;
}

export const flowTaskApi = {
  pending: () => http.get<any, FlowTaskItem[]>('/design/flow-tasks/pending'),
  all: () => http.get<any, FlowTaskItem[]>('/design/flow-tasks/all'),
  detail: (id: number) => http.get<any, FlowTaskItem>(`/design/flow-tasks/${id}`),
  approve: (id: number, body: ApproveTaskReq = {}) =>
    http.post<any, FlowTaskItem>(`/design/flow-tasks/${id}/approve`, body),
  reject: (id: number, body: ApproveTaskReq = {}) =>
    http.post<any, FlowTaskItem>(`/design/flow-tasks/${id}/reject`, body),
};

export const flowInstanceApi = {
  list: () => http.get<any, FlowInstanceItem[]>('/design/flow-instances'),
  detail: (id: number) => http.get<any, FlowInstanceItem>(`/design/flow-instances/${id}`),
  start: (body: StartFlowReq) => http.post<any, FlowInstanceItem>('/design/flow-instances/start', body),
};