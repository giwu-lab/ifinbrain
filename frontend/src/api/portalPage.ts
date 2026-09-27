import { http } from './http';

export interface PortalPage {
  id: number;
  code: string;
  name: string;
  description?: string;
  layoutSchema: string;
  configVersion: number;
  status: 0 | 1;
  roleBindings: string[];
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
  publishedAt?: string | null;
}

export interface PortalBlockMeta {
  id: number;
  type: string;
  name: string;
  category: string;
  icon: string;
  sort: number;
  enabled: 0 | 1;
  builtIn: 0 | 1;
  defaultConfig: string;
  configSchema: string;
}

export interface PortalResolveResp {
  hit: boolean;
  id?: number;
  code?: string;
  name?: string;
  layoutSchema?: string;
}

export interface PortalCreateReq {
  code: string;
  name: string;
  description?: string;
  layoutSchema?: string;
  roleBindings?: string[];
  status?: 0 | 1;
}

export interface PortalUpdateReq {
  name?: string;
  description?: string;
  layoutSchema?: string;
  roleBindings?: string[] | null;
  status?: 0 | 1;
}

export const portalPageApi = {
  list:     () => http.get<unknown, PortalPage[]>('/design/portal/pages'),
  detail:   (id: number) => http.get<unknown, PortalPage>(`/design/portal/pages/${id}`),
  create:   (body: PortalCreateReq) => http.post<unknown, PortalPage>('/design/portal/pages', body),
  update:   (id: number, body: PortalUpdateReq) => http.put<unknown, PortalPage>(`/design/portal/pages/${id}`, body),
  publish:  (id: number) => http.post<unknown, PortalPage>(`/design/portal/pages/${id}/publish`, {}),
  unpublish:(id: number) => http.post<unknown, PortalPage>(`/design/portal/pages/${id}/unpublish`, {}),
  remove:   (id: number) => http.delete(`/design/portal/pages/${id}`),

  blocks:   () => http.get<unknown, PortalBlockMeta[]>('/design/portal/blocks'),

  /** 运行时：按当前用户角色命中首个已发布门户页 */
  resolve:  () => http.get<unknown, PortalResolveResp>('/portal/resolve'),
};