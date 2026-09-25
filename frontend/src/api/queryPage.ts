import { http } from './http';
import type { QueryItem } from './query';

/**
 * 查询页面（ad-hoc 一键生成的查询列表页）API。
 *
 * 命名说明：
 *   - 所有 publish/unpublish/republish/updatePage/deletePage 接收 **queryId**
 *   - resolveQueryId/detail 接收 **menuId**（仅供生成的查询列表页 /data-mgmt/query-page/{menuId} 启动时用）
 */

export interface QueryPageConfig {
  paramLabels: Record<string, string>;
  columns: Record<string, { headerText: string; width: number | null }>;
}

export interface PublishedPageMeta {
  id: number;
  name: string;
  path: string;
  component: string;
  permission: string;
  status: number;
  queryId: number;
  icon?: string | null;
  sort?: number;
  configJson?: string;
}

export const queryPageApi = {
  /** 一键发布 */
  publish: (queryId: number, menuName?: string, configJson?: string, icon?: string, sort?: number) =>
    http.post<any, PublishedPageMeta>(`/datamgmt/queries/${queryId}/publish`, {
      menuName: menuName || null,
      icon: icon || null,
      sort: sort ?? null,
      configJson: configJson || null,
    }),

  /** 编辑已发布（menuName / icon / sort / configJson） */
  updatePage: (queryId: number, menuName: string, configJson: string, icon?: string, sort?: number) =>
    http.put<any, PublishedPageMeta>(`/datamgmt/queries/${queryId}/page`, {
      menuName,
      icon: icon ?? null,
      sort: sort ?? null,
      configJson,
    }),

  /** 软取消发布（status=0） */
  unpublish: (queryId: number) => http.post<any, PublishedPageMeta>(`/datamgmt/queries/${queryId}/unpublish`),

  /** 恢复发布 */
  republish: (queryId: number) => http.post<any, PublishedPageMeta>(`/datamgmt/queries/${queryId}/republish`),

  /** 硬删除（永久移除 menu 行） */
  deletePage: (queryId: number) => http.delete<any, null>(`/datamgmt/queries/${queryId}/page`),

  /** 给生成的查询列表页用：menuId → queryId */
  resolveQueryId: (menuId: number) => http.get<any, number>(`/datamgmt/query-pages/${menuId}/query-id`),

  /** 给生成的查询列表页用：拿 menu + configJson */
  detail: (menuId: number) => http.get<any, PublishedPageMeta>(`/datamgmt/query-pages/${menuId}`),
};

export function parseConfig(s: string | undefined | null): QueryPageConfig {
  if (!s) return { paramLabels: {}, columns: {} };
  try {
    const o = JSON.parse(s);
    return {
      paramLabels: o?.paramLabels ?? {},
      columns: o?.columns ?? {},
    };
  } catch { return { paramLabels: {}, columns: {} }; }
}

/** 从 QueryItem 中取出 published 相关的元数据（强类型） */
export interface QueryItemWithPage extends QueryItem {
  publishedMenuId?: number | null;
  publishedMenuName?: string | null;
  publishedMenuPath?: string | null;
  publishedMenuStatus?: number | null;
  publishedMenuIcon?: string | null;
  publishedMenuSort?: number;
}