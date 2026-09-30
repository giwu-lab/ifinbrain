import dayjs from 'dayjs';

/**
 * 格式化日期时间为 yyyy-MM-dd HH:mm:ss。
 * 输入为 null/undefined/空字符串时返回 '—'（en-dash 占位）。
 */
export function formatDateTime(s?: string | null): string {
  if (!s) return '—';
  const d = dayjs(s);
  if (!d.isValid()) return String(s);
  return d.format('YYYY-MM-DD HH:mm:ss');
}

/** 格式化日期为 yyyy-MM-dd */
export function formatDate(s?: string | null): string {
  if (!s) return '—';
  const d = dayjs(s);
  if (!d.isValid()) return String(s);
  return d.format('YYYY-MM-DD');
}