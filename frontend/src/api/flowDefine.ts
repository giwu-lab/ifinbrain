import { http } from './http';

/** 节点类型（与后端 NodeType 同步） */
export type NodeType = 'START' | 'END' | 'APPROVAL' | 'CONDITION';

/** 审批模式 */
export type ApproveType = 'OR' | 'AND' | 'PERCENT';

/** 条件分支 */
export interface FlowBranch {
  label: string;
  condition: string;  // 空=默认；approved/rejected=系统常量；${...}=SpEL
}

/** 节点业务数据 */
export interface FlowNodeData {
  type: NodeType;
  label: string;
  description?: string;
  userIds?: string[];
  roleIds?: string[];
  orgIds?: string[];
  approvalType?: ApproveType;
  completionPercent?: number;
  branches?: FlowBranch[];
  /** 节点挂表单（iFinBrain 扩展，覆盖流程默认表单） */
  formCode?: string;
}

/** 流程节点（Vue Flow node） */
export interface FlowNode {
  id: string;
  type?: string;  // Vue Flow 节点类型：'custom' | 'input' | 'output'
  position?: { x: number; y: number };
  data: FlowNodeData;
}

/** 连线业务数据 */
export interface FlowEdgeData {
  condition?: string;
}

/** 流程连线（Vue Flow edge） */
export interface FlowEdge {
  id: string;
  source: string;
  target: string;
  data?: FlowEdgeData;
  /** Vue Flow edge 类型，可选 'smoothstep' / 'default' */
  type?: string;
}

/** FlowJson 根对象（与 mfish 兼容） */
export interface FlowJson {
  nodes: FlowNode[];
  edges: FlowEdge[];
}

/** 流程定义 DTO */
export interface FlowDefineItem {
  id: number;
  code: string;
  name: string;
  description?: string;
  flowJson?: FlowJson;
  flowJsonRaw?: string;
  defaultFormCode?: string;
  status: number;
  version: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  nodeCount?: number;
  edgeCount?: number;
}

export interface FlowDefineCreateBody {
  code: string;
  name: string;
  description?: string;
  flowJson?: FlowJson;
  defaultFormCode?: string;
  status?: number;
}

export interface FlowDefineUpdateBody {
  code?: string;
  name?: string;
  description?: string;
  flowJson?: FlowJson;
  defaultFormCode?: string;
  status?: number;
}

export const flowDefineApi = {
  list: () => http.get<any, FlowDefineItem[]>('/design/flows'),
  /** 当前用户「可发起」流程清单：status=1 + 当前用户有流程级 deploy perm */
  launchable: () => http.get<any, FlowDefineItem[]>('/design/flows/launchable'),
  detail: (id: number) => http.get<any, FlowDefineItem>(`/design/flows/${id}`),
  detailByCode: (code: string) => http.get<any, FlowDefineItem>(`/design/flows/by-code/${code}`),
  create: (body: FlowDefineCreateBody) =>
    http.post<any, FlowDefineItem>('/design/flows', body),
  update: (id: number, body: FlowDefineUpdateBody) =>
    http.put<any, FlowDefineItem>(`/design/flows/${id}`, body),
  remove: (id: number) => http.delete<any, null>(`/design/flows/${id}`),
  publish: (id: number) => http.post<any, FlowDefineItem>(`/design/flows/${id}/publish`),
  unpublish: (id: number) => http.post<any, FlowDefineItem>(`/design/flows/${id}/unpublish`),
  /** 流程授权：4 verb（view/update/deploy/delete）的角色绑定 */
  getPermissions: (id: number) =>
    http.get<any, Record<string, number[]>>(`/design/flows/${id}/permissions`),
  updatePermissions: (id: number, grants: Record<string, number[]>) =>
    http.put<any, null>(`/design/flows/${id}/permissions`, grants),
};

/** 节点类型展示元数据 */
export const NODE_TYPE_META: Record<NodeType, { icon: string; label: string; color: string; bg: string }> = {
  START:     { icon: 'VideoPlay',  label: '开始',     color: '#0e8f5c', bg: '#e6f4ea' },
  END:       { icon: 'CircleClose',label: '结束',     color: '#8a8a85', bg: '#f0f0eb' },
  APPROVAL:  { icon: 'User',       label: '审批节点', color: '#5e35b1', bg: '#ede7f6' },
  CONDITION: { icon: 'SetUp',      label: '条件分支', color: '#e08800', bg: '#fff3e0' },
};

export const NODE_TYPE_LIST: NodeType[] = ['START', 'APPROVAL', 'CONDITION', 'END'];

/** 默认节点位置（dagre 自动布局前的初始位置） */
export function defaultNodePosition(type: NodeType, index: number): { x: number; y: number } {
  if (type === 'START') return { x: 100, y: 100 };
  if (type === 'END') return { x: 100, y: 100 + 80 * (index + 2) };
  return { x: 100, y: 100 + 80 * (index + 1) };
}