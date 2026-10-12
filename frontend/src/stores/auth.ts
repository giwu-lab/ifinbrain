import { defineStore } from 'pinia';
import { authApi } from '@/api/auth';
import type { UserInfo } from '@/types';
import { useTabStore } from '@/stores/tab';
import { useThemeStore } from '@/stores/theme';

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
      // 登录后从 DB 拉一次主题偏好 —— 失败时静默回退到 localStorage 缓存。
      // 传入用户名：DB 无记录时用它判断本地缓存是不是「上一位用户的」。
      try {
        await useThemeStore().hydrate(data.user?.username);
      } catch { /* 离线模式：保留上次缓存 */ }
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
      // 清掉多标签页：登出后不允许保留上一用户的 tab 痕迹
      // tabStore 不依赖 auth，不会形成循环
      try { useTabStore().reset(); } catch { /* store 还未注入，忽略 */ }
      // 清掉主题缓存：同一浏览器换人登录时，不允许把上一位用户的配色带过去
      try { useThemeStore().purge(); } catch { /* store 还未注入，忽略 */ }
    },
    hasPerm(code: string): boolean {
      return this.permissions.includes(code);
    },
    hasAnyPerm(codes: string[]): boolean {
      return codes.some((c) => this.permissions.includes(c));
    }
  }
});