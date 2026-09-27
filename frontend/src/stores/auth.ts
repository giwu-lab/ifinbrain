import { defineStore } from 'pinia';
import { authApi } from '@/api/auth';
import type { UserInfo } from '@/types';

interface AuthState {
  token: string;
  refreshToken: string;
  user: UserInfo | null;
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem('accessToken') || '',
    refreshToken: localStorage.getItem('refreshToken') || '',
    user: null
  }),
  getters: {
    isLogged: (s) => !!s.token,
    permissions: (s) => s.user?.permissions || [],
    roles: (s) => s.user?.roles || [],
    menus: (s) => s.user?.menus || []
  },
  actions: {
    async login(username: string, password: string) {
      const data = await authApi.login(username, password);
      this.token = data.accessToken;
      this.refreshToken = data.refreshToken;
      this.user = data.user;
      localStorage.setItem('accessToken', data.accessToken);
      localStorage.setItem('refreshToken', data.refreshToken);
    },
    async fetchMe() {
      const info = await authApi.me();
      this.user = info;
      return info;
    },
    /** 用 refreshToken 换一对新 access/refresh —— 用于「服务端权限变更后」让浏览器侧 JWT 也立刻带新 perm。
     *  例如 admin 在档案库新建了档案库 / 改了授权 → seedAdminAccess 给了 admin 新 perm，
     *  但当前 localStorage.accessToken 里没有 → 下次调 API 会 403。 */
    async refreshTokens() {
      if (!this.refreshToken) return null;
      const data = await authApi.refresh(this.refreshToken);
      this.token = data.accessToken;
      this.refreshToken = data.refreshToken;
      this.user = data.user;
      localStorage.setItem('accessToken', data.accessToken);
      localStorage.setItem('refreshToken', data.refreshToken);
      return data;
    },
    async logout() {
      try { await authApi.logout(); } catch {}
      this.clear();
    },
    clear() {
      this.token = '';
      this.refreshToken = '';
      this.user = null;
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
    },
    hasPerm(code: string): boolean {
      return this.permissions.includes(code);
    },
    hasAnyPerm(codes: string[]): boolean {
      return codes.some((c) => this.permissions.includes(c));
    }
  }
});