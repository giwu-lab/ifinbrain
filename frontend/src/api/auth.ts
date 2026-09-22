import { http } from './http';
import type { LoginResp, UserInfo } from '@/types';

export const authApi = {
  login: (username: string, password: string) =>
    http.post<unknown, LoginResp>('/auth/login', { username, password }),
  refresh: (refreshToken: string) =>
    http.post<unknown, LoginResp>('/auth/refresh', { refreshToken }),
  logout: () => http.post('/auth/logout'),
  me: () => http.get<unknown, UserInfo>('/auth/me')
};