import { http } from './http';
import type { PageResp } from '@/types';

export interface UserItem {
  id: number;
  username: string;
  displayName?: string;
  email?: string;
  phone?: string;
  /** 所属部门 id；null/undefined = 未归属任何部门 */
  deptId?: number | null;
  /** 所属部门名称；后端解析，部门被删时为 null */
  deptName?: string | null;
  status: number;
  lastLoginAt?: string;
  createdAt?: string;
  roleIds: number[];
  roleNames: string[];
}

export interface RoleItem {
  id: number;
  code: string;
  name: string;
  remark?: string;
  status: number;
  createdAt?: string;
  permissionIds: number[];
  permissionCodes: string[];
}

export interface PermissionItem {
  id: number;
  code: string;
  name: string;
  type: string;
}

/** 组织架构（部门）树节点 —— 同时带 label/value 别名供 el-tree-select 默认 props 消费 */
export interface DeptItem {
  id: number;
  parentId: number;
  name: string;
  code?: string;
  leader?: string;
  phone?: string;
  sort: number;
  status: number;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
  /** = name */
  label?: string;
  /** = id */
  value?: number;
  children?: DeptItem[];
}

export interface DeptPayload {
  name: string;
  parentId?: number;
  code?: string;
  leader?: string;
  phone?: string;
  sort?: number;
  status?: number;
  remark?: string;
}

export interface MenuItem {
  id: number;
  parentId: number;
  name: string;
  path?: string;
  component?: string;
  icon?: string;
  sort: number;
  permission?: string;
  type: string;
  status: number;
  children?: MenuItem[];
}

export const userApi = {
  list: (params: { page?: number; size?: number; keyword?: string }) =>
    http.get<unknown, PageResp<UserItem>>('/system/users', { params }),
  detail: (id: number) => http.get<unknown, UserItem>(`/system/users/${id}`),
  create: (data: Partial<UserItem> & { password: string; roleIds: number[] }) =>
    http.post<unknown, number>('/system/users', data),
  update: (id: number, data: Partial<UserItem> & { password?: string; roleIds?: number[] }) =>
    http.put(`/system/users/${id}`, data),
  remove: (id: number) => http.delete(`/system/users/${id}`)
};

export const roleApi = {
  list: () => http.get<unknown, RoleItem[]>('/system/roles'),
  detail: (id: number) => http.get<unknown, RoleItem>(`/system/roles/${id}`),
  permissions: () => http.get<unknown, PermissionItem[]>('/system/permissions'),
  create: (data: Partial<RoleItem> & { permissionIds: number[] }) =>
    http.post<unknown, number>('/system/roles', data),
  update: (id: number, data: Partial<RoleItem> & { permissionIds?: number[] }) =>
    http.put(`/system/roles/${id}`, data),
  remove: (id: number) => http.delete(`/system/roles/${id}`)
};

export const menuApi = {
  tree: () => http.get<unknown, MenuItem[]>('/system/menus/tree'),
  flat: () => http.get<unknown, MenuItem[]>('/system/menus'),
  create: (data: Partial<MenuItem>) => http.post<unknown, number>('/system/menus', data),
  update: (id: number, data: Partial<MenuItem>) => http.put(`/system/menus/${id}`, data),
  remove: (id: number) => http.delete(`/system/menus/${id}`)
};

export const permissionApi = {
  list: () => http.get<unknown, PermissionItem[]>('/system/permissions')
};

export const deptApi = {
  tree: () => http.get<unknown, DeptItem[]>('/system/depts/tree'),
  list: () => http.get<unknown, DeptItem[]>('/system/depts'),
  detail: (id: number) => http.get<unknown, DeptItem>(`/system/depts/${id}`),
  create: (data: DeptPayload) => http.post<unknown, number>('/system/depts', data),
  update: (id: number, data: Partial<DeptPayload>) => http.put(`/system/depts/${id}`, data),
  remove: (id: number) => http.delete(`/system/depts/${id}`)
};