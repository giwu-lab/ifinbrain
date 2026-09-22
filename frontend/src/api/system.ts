import { http } from './http';
import type { PageResp } from '@/types';

export interface UserItem {
  id: number;
  username: string;
  displayName?: string;
  email?: string;
  phone?: string;
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