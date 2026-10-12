import dayjs from 'dayjs';

/**
 * 格式化日期时间为 **yyyy-MM-dd HH:mm:ss** —— 全项目时间戳的唯一标准（AGENTS §8.3）。
 *
 * <p>传 string / number（毫秒时间戳）/ Date 都行：字典页的「缓存时间」存的是
 * `Date.now()` 毫秒数，直接传进来即可，不必在页面里先 new Date 再转字符串。
 *
 * <p>入参为空返回 {@code '—'}（en-dash 占位）；无法解析时原样返回入参字符串，
 * 而不是抛错或显示 `Invalid Date` —— 脏数据不该让整列表页崩掉。
 */
export function formatDateTime(s?: string | number | Date | null): string {
  if (s === null || s === undefined || s === '') return '—';
  const d = dayjs(s);
  if (!d.isValid()) return String(s);
  return d.format('YYYY-MM-DD HH:mm:ss');
}

/**
 * 格式化**纯日期**为 **yyyy-MM-dd**（不含时分秒）。
 *
 * <p>与 {@link formatDateTime} 配套使用：凡是显示「某天」语义的地方（创建日期、
 * 账期、归档日等）走本函数，凡是显示「某一刻」的地方走 formatDateTime。
 * 两者不要混用 —— 只显示日期却用 formatDateTime 会多出一串无意义的时间。
 */
export function formatDate(s?: string | number | Date | null): string {
  if (s === null || s === undefined || s === '') return '—';
  const d = dayjs(s);
  if (!d.isValid()) return String(s);
  return d.format('YYYY-MM-DD');
}

/**
 * 今天的 **yyyy-MM-dd** —— 「默认当天」类表单初始值的统一出口。
 *
 * <p>过去各页面各自手写 `new Date()` + padStart 拼日期串，格式容易和
 * el-date-picker 的 value-format 对不上（一个给 `2026/10/09` 一个收 `2026-10-09`）。
 * 凡是 el-date-picker value-format="YYYY-MM-DD" 的初始值都从这里取，格式必然对齐。
 */
export function today(): string {
  return dayjs().format('YYYY-MM-DD');
}