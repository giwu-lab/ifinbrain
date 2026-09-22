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