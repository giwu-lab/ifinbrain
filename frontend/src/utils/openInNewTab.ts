/**
 * 在新浏览器**标签页**打开应用内路径。
 *
 * ## 「标签页」和「弹出窗口」的区别只有 features 这一个参数
 *
 *     window.open(url, '_blank')                      // 新标签页：有标签栏，可 Ctrl+W，可拖出来
 *     window.open(url, '_blank', 'width=1600,height=900')  // 弹出窗口：无标签栏，像个独立 App
 *
 * 第三个参数里只要出现 width / height（以及 top / left / menubar 等任一「窗口特征」），
 * 浏览器就会走弹窗路径而不是开标签页。所以这里**必须留空** —— 传一个空字符串
 * 效果等同于不传，但可读性差，直接不传。
 *
 * ## 为什么不用 noopener feature
 *
 * 规范规定：features 里只要带 `noopener`，window.open 就**返回 null**（用于切断
 * `新页面 → window.opener → 原页面` 的引用链）。于是 `if (win)` 永远进不去，
 * 「有没有被弹窗拦截」判断不出来，降级也就无从谈起。
 *
 * 所以改为开窗成功后手动 `win.opener = null`：同源场景下手动置空与 noopener 等效，
 * 代价是换来可用的返回值。目标页面是本项目自己的 SPA，不存在跨域失效的问题。
 */
import type { Router } from 'vue-router';

export interface NewTabOptions {
  /** 弹窗被浏览器拦截时调用（用户关掉了本站弹窗权限 / 不在用户手势里） */
  onBlocked?: () => void;
}

/**
 * 在新标签页打开站内路径。
 *
 * @param path 站内路径，如 `/bi/report/view/1`
 * @param router 可选。传了走 router.resolve 拼路径，history base 变化时也不会拼错
 * @returns 是否成功打开。false = 被拦截（已按需触发 onBlocked）
 */
export function openInNewTab(path: string, opts: NewTabOptions = {}, router?: Router): boolean {
  const resolved = router ? router.resolve(path).href : path;
  const url = new URL(resolved, window.location.origin).toString();

  // 第三个参数（features）刻意留空 —— 见文件头：一旦带上 width/height 就会变成弹出窗口
  const win = window.open(url, '_blank');
  if (!win) {
    opts.onBlocked?.();
    return false;
  }
  win.opener = null;
  return true;
}
