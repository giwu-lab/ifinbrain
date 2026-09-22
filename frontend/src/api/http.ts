import axios, { AxiosError, AxiosRequestConfig } from 'axios';
import { ElMessage } from 'element-plus';
import type { Result } from '@/types';

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
    const original = err.config as AxiosRequestConfig & { _retry?: boolean };

    if (status === 401 && !original._retry) {
      const refresh = localStorage.getItem('refreshToken');
      if (refresh && !isRefreshing) {
        isRefreshing = true;
        try {
          const r = await axios.post<Result<{ accessToken: string; refreshToken: string }>>(
            `${baseURL}/auth/refresh`,
            { refreshToken: refresh }
          );
          const data = r.data.data;
          if (data) {
            localStorage.setItem('accessToken', data.accessToken);
            localStorage.setItem('refreshToken', data.refreshToken);
            pendingQueue.forEach((cb) => cb(data.accessToken));
            pendingQueue = [];
            original._retry = true;
            original.headers = { ...(original.headers || {}), Authorization: `Bearer ${data.accessToken}` };
            return http(original);
          }
        } catch (e) {
          pendingQueue.forEach((cb) => cb(null));
          pendingQueue = [];
          localStorage.removeItem('accessToken');
          localStorage.removeItem('refreshToken');
          window.location.href = '/login';
        } finally {
          isRefreshing = false;
        }
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
    const msg = (body && body.message) || err.message || 'Network error';
    ElMessage.error(msg);
    return Promise.reject(err);
  }
);