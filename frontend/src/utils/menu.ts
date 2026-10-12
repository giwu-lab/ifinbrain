/**
 * 菜单显示名解析 —— 侧边栏 / 顶栏 / 发布对话框共用一份。
 *
 * <p><b>为什么要抽出来</b>：这段逻辑原本在 4 个 layout 里各抄了一份，
 * 改一次要改四处，漏一处就会出现「侧边栏是中文、顶栏是英文」。
 * 发布对话框还要再抄第五份 —— 那就让目录名直接显示 DB 里的英文原文。
 *
 * <p><b>为什么需要它</b>：`sys_menu.name` 是给管理员看的**中文兜底**
 * （V53/V55 迁移里塞的是 Invoice / BI Analytics 这类英文），
 * 真正面向用户的名字走 i18n key。按语言切换时侧边栏能变中文，
 * 发布对话框里的目录下拉不变 —— 那就割裂了。
 *
 * <p>候选 key 的优先级（与历史行为完全一致，顺序不能调）：
 * <ol>
 *   <li>{@code menu.{component}} —— 叶子菜单的权威 key，如
 *       {@code menu.data-mgmt.query.Index}；</li>
 *   <li>{@code menu.{path 去首斜杠}} —— 兼容 {@code menu.invoiceUpload} 这类老式平铺 key；</li>
 *   <li>{@code menu.{path 去首斜杠且斜杠转点}._} —— 目录的权威 key（{@code menu.bi._}）；</li>
 * </ol>
 * 全都不命中才回落到 DB 里的 {@code name}。
 *
 * <p><b>回落是合法结果，不是 bug</b>：档案库菜单（{@code /documents/{slug}}）
 * 所有库共用一个 component，显示名是管理员填的库名 —— 那是用户数据不是界面文案。
 * 覆盖情况用 {@code node scripts/menu-i18n-audit.mjs} 审计，别靠肉眼。
 */

/** 菜单最小形态：只用到解析名字所需的三个字段 */
export interface MenuNameSource {
  name: string;
  component?: string | null;
  path?: string | null;
}

/** 极简 t 签名：只要「查不到就原样返回 key」这个行为，够用且不绑 vue-i18n */
export type TranslateFn = (key: string) => string;

/**
 * 按 i18n key 解析菜单显示名，命中不了就回落到 `m.name`。
 *
 * @param t `useI18n()` 里的 `t`。注意 vue-i18n 的 `t` 在缺 key 时可能返回
 *          数组/对象，模板里会渲染成 `[object Object]` —— 所以下面用
 *          `typeof translated === 'string'` 一起挡掉。
 */
export function resolveMenuName(m: MenuNameSource, t: TranslateFn): string {
  const comp = m.component?.replace(/\//g, '.');
  const path = m.path && m.path !== '/' ? m.path.replace(/^\//, '') : null;

  const candidates = [
    comp ? `menu.${comp}` : null,
    path ? `menu.${path}` : null,
    path ? `menu.${path.replace(/\//g, '.')}._` : null,
  ].filter(Boolean) as string[];

  for (const key of candidates) {
    const translated = t(key);
    // 命中的判据是「结果不是 key 本身」——缺 key 时 vue-i18n 原样返回
    if (typeof translated === 'string' && translated !== key && translated.trim() !== '') {
      return translated;
    }
  }
  return m.name;
}