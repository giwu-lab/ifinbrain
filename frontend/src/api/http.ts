import axios, { AxiosError, AxiosRequestConfig } from 'axios';
import { ElMessage } from 'element-plus';
import type { Result } from '@/types';
import i18n from '@/i18n';

let isRefreshing = false;
let pendingQueue: Array<(token: string | null) => void> = [];

const baseURL = import.meta.env.VITE_API_BASE || '/api';

export const http = axios.create({
  baseURL,
  timeout: 60_000
});

http.interceptors.request.use((cfg) => {
  const token = localStorage.getItem('accessToken');
  if (token) {
    cfg.headers.Authorization = `Bearer ${token}`;
  }
  return cfg;
});

http.interceptors.response.use(
  (resp) => {
    const body = resp.data as Result<unknown>;
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data as any;
      ElMessage.error(body.message || 'Request failed');
      return Promise.reject(new Error(body.message || 'Request failed'));
    }
    return resp.data;
  },
  async (err: AxiosError<Result<unknown>>) => {
    const status = err.response?.status;
    const body = err.response?.data;
    const original = err.config as AxiosRequestConfig & { _retry?: boolean; _retry403?: boolean };

    // 共用的 refresh + 重试：401 和 403 都用同一段（403 触发场景：
    // 服务端扩展了权限，旧 JWT 里没有 → refresh 拿到新 perms → 重试成功）
    async function tryRefreshAndRetry() {
      const refresh = localStorage.getItem('refreshToken');
      if (!refresh || original._retry || original._retry403) return null;
      try {
        const r = await axios.post<Result<{ accessToken: string; refreshToken: string }>>(
          `${baseURL}/auth/refresh`,
          { refreshToken: refresh }
        );
        const data = r.data.data;
        if (!data) return null;
        localStorage.setItem('accessToken', data.accessToken);
        localStorage.setItem('refreshToken', data.refreshToken);
        return data.accessToken as string;
      } catch {
        return null;
      }
    }

    if (status === 401 && !original._retry) {
      const refresh = localStorage.getItem('refreshToken');
      if (refresh && !isRefreshing) {
        isRefreshing = true;
        try {
          const newToken = await tryRefreshAndRetry();
          if (newToken) {
            pendingQueue.forEach((cb) => cb(newToken));
            pendingQueue = [];
            original._retry = true;
            original.headers = { ...(original.headers || {}), Authorization: `Bearer ${newToken}` };
            return http(original);
          }
        } finally {
          isRefreshing = false;
        }
        pendingQueue.forEach((cb) => cb(null));
        pendingQueue = [];
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        window.location.href = '/login';
      } else if (isRefreshing) {
        return new Promise((resolve) => {
          pendingQueue.push((token) => {
            if (token) {
              original._retry = true;
              original.headers = { ...(original.headers || {}), Authorization: `Bearer ${token}` };
              resolve(http(original));
            } else {
              resolve(Promise.reject(err));
            }
          });
        });
      } else {
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        if (window.location.pathname !== '/login') {
          window.location.href = '/login';
        }
      }
    }

    // 403：可能是"JWT 过期（DB 加了新 perm，但旧 JWT 里没有）"。
    // 一次 refresh 机会：拿到新 perms 后重试；仍 403 → 真无权限。
    if (status === 403 && !original._retry403) {
      const newToken = await tryRefreshAndRetry();
      if (newToken) {
        original._retry403 = true;
        original.headers = { ...(original.headers || {}), Authorization: `Bearer ${newToken}` };
        // 同步刷新 Pinia auth.user.permissions（让 UI 按钮 / 菜单也立刻生效）
        try {
          const { useAuthStore } = await import('@/stores/auth');
          const auth = useAuthStore();
          if (auth.token) {
            await auth.fetchMe();
            auth.token = newToken; // 触发依赖 token 的 computed 重算
          }
        } catch { /* ignore — 不影响主请求重试 */ }
        return http(original);
      }
    }

    const msg = (body && body.message) || err.message || i18n.global.t('error.networkError');
    ElMessage.error(msg);
    return Promise.reject(err);
  }
);