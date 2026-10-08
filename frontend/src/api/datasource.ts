import { http } from './http';
import type { PageResp } from '@/types';

export type DbType = 'mysql' | 'mssql' | 'postgresql';
export type PasswordStatus = 'missing' | 'ok' | 'failed';

export interface Datasource {
  id: number;
  name: string;
  dbType: DbType;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  /** 永远只回掩码，前端拿不到明文 */
  passwordMask?: string;
  /** 'missing' = 未配置 / 'ok' = 已存可解密 / 'failed' = 密钥失效 */
  passwordStatus: PasswordStatus;
  remark?: string;
  status: 0 | 1;
  lastTestOk?: 0 | 1 | null;
  lastTestAt?: string | null;
  lastTestMsg?: string | null;
  createdAt?: string;
  updatedAt?: string;

  // 前端运行时临时字段（仅组件内部使用，不来自后端）
  __testing?: boolean;
  __lastTestResult?: TestResult;
}

export interface TestResult {
  ok: boolean;
  version?: string;
  latencyMs?: number;
  message?: string;
}

export const datasourceApi = {
  /** 列表页：后端真分页。keyword 命中名称 / 主机 / 库名 / 账号 / 备注 */
  list:    (params?: { page?: number; size?: number; keyword?: string }) =>
    http.get<unknown, PageResp<Datasource>>('/datamgmt/datasources', { params }),

  /**
   * 全量列表：仅供「下拉选数据源」（查询详情页、导入向导）。
   * 下拉必须看到全部项才渲染得全，所以后端这个入口刻意不分页。
   */
  listAll: () => http.get<unknown, Datasource[]>('/datamgmt/datasources/options'),

  detail:  (id: number) => http.get<unknown, Datasource>(`/datamgmt/datasources/${id}`),
  create:  (body: {
    name: string; dbType: DbType; host: string; port: number;
    databaseName: string; username: string; password: string;
    remark?: string; status?: 0 | 1;
  }) => http.post<unknown, Datasource>('/datamgmt/datasources', body),
  update:  (id: number, body: {
    name?: string; dbType?: DbType; host?: string; port?: number;
    databaseName?: string; username?: string; password?: string;
    remark?: string; status?: 0 | 1;
  }) => http.put(`/datamgmt/datasources/${id}`, body),
  remove:  (id: number) => http.delete(`/datamgmt/datasources/${id}`),

  /** 测已存数据源：解密 + 连通 + 写回 lastTest*；返回结果行内展示 */
  test:    (id: number) => http.post<unknown, TestResult>(`/datamgmt/datasources/${id}/test`, {}),

  /** 测表单输入（创建/编辑时实时验证；不写库） */
  testNew: (body: {
    dbType: DbType; host: string; port: number;
    databaseName: string; username: string; password: string;
  }) => http.post<unknown, TestResult>('/datamgmt/datasources/test', body),
};