import axios, { AxiosError, AxiosRequestConfig } from 'axios';
import { ElMessage } from 'element-plus';
import type { Result } from '@/types';
import i18n from '@/i18n';

/**
 * 单例 refresh Promise —— 同一时刻只有一个 refresh 调用在跑（review M2 修复）。
 * 旧实现：isRefreshing + pendingQueue —— A 拿到 token 后 return http(original)（异步），
 *   finally 等 A 的 promise resolve 才 isRefreshing=false，期间 B 进 pendingQueue
 *   永远不被消费。
 * 新实现：所有 401 都 await 同一个 refreshingPromise。
 *   - Promise resolve = 拿到新 token（每个 awaiter 各自用）
 *   - Promise resolve(null) = refresh 失败（统一跳 login）
 *   - finally 中隔一个 microtask 再清空，让本 tick 内 push 的"尾随请求"先 attach 到队列
 *     （避免竞态）。不过单例模式下没有 pendingQueue 概念了，无需担心。
 */
let refreshingPromise: Promise<string | null> | null = null;

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

/**
 * 共用 refresh —— 401 和 403 都用。
 * 403 触发场景：服务端扩展了权限，旧 JWT 里没有 → refresh 拿到新 perms → 重试成功
 */
async function refreshTokens(): Promise<string | null> {
  if (refreshingPromise) return refreshingPromise;
  const refresh = localStorage.getItem('refreshToken');
  if (!refresh) return null;
  refreshingPromise = (async () => {
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
    } finally {
      // 隔一个 microtask 清空，让 await 链路上"刚返回"的所有 caller 先拿到 token 再重置
      await Promise.resolve();
      refreshingPromise = null;
    }
  })();
  return refreshingPromise;
}

http.interceptors.response.use(
  (resp) => {
    const body = resp.data as Result<unknown>;
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data as any;
      const msg = body.message || 'Request failed';
      // _silent：调用方自己接管错误展示（如 RAG 问答在抽屉内联显示失败原因），不重复弹 toast
      if (!(resp.config as AxiosRequestConfig & { _silent?: boolean })?._silent) {
        ElMessage.error(msg);
      }
      return Promise.reject(new Error(msg));
    }
    return resp.data;
  },
  async (err: AxiosError<Result<unknown>>) => {
    const status = err.response?.status;
    const body = err.response?.data;
    const original = err.config as AxiosRequestConfig & { _retry?: boolean; _retry403?: boolean };

    if (status === 401 && !original._retry) {
      const refresh = localStorage.getItem('refreshToken');
      if (!refresh) {
        if (window.location.pathname !== '/login') {
          window.location.href = '/login';
        }
        return Promise.reject(err);
      }
      const newToken = await refreshTokens();
      if (newToken) {
        original._retry = true;
        original.headers = { ...(original.headers || {}), Authorization: `Bearer ${newToken}` };
        return http(original);
      }
      // refresh 失败：单例 promise 已经 resolve(null)，清状态 + 跳 login
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
      return Promise.reject(err);
    }

    // 403：可能是"JWT 过期（DB 加了新 perm，但旧 JWT 里没有）"。
    // 一次 refresh 机会：拿到新 perms 后重试；仍 403 → 真无权限。
    if (status === 403 && !original._retry403) {
      const newToken = await refreshTokens();
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

    // 静默 403：标记 _silent403 的请求（如后台预加载）失败时不弹 toast。
    // 适用于 dict/i18n 缓存预加载等场景 —— 用户没该 perm 时理应静默，不打扰。
    // _silent 覆盖面更广：调用方自行渲染错误（如 RAG 问答抽屉内联展示原因）。
    const silent = (original as any)._silent || (original as any)._silent403;

    const msg = (body && body.message) || err.message || i18n.global.t('error.networkError');
    if (!silent) ElMessage.error(msg);
    return Promise.reject(err);
  }
);