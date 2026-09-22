export interface Result<T = unknown> {
  code: number;
  message: string;
  data: T;
  timestamp?: number;
}

export interface MenuNode {
  id: number;
  parentId: number;
  name: string;
  path?: string;
  component?: string;
  icon?: string;
  sort?: number;
  permission?: string;
  type: string;
  children?: MenuNode[];
}

export interface UserInfo {
  id: number;
  username: string;
  displayName?: string;
  email?: string;
  phone?: string;
  roles: string[];
  permissions: string[];
  menus: MenuNode[];
}

export interface LoginResp {
  accessToken: string;
  refreshToken: string;
  accessTtlSeconds: number;
  refreshTtlSeconds: number;
  user: UserInfo;
}

export interface PageResp<T> {
  total: number;
  page: number;
  size: number;
  items: T[];
}
