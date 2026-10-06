/**
 * Permission code 解析工具。
 *
 * permission code 约定：`<module>:<resource>:<action>` 三段式
 *   - module:   'system' | 'invoice' | 'data' | 'i18n' | 'dict' | 'parser' | 'setting' | 'file'
 *   - resource: 'user' | 'role' | 'menu' | 'perm' | 'datasource' | 'query' | ...
 *   - action:   'list' | 'view' | 'create' | 'update' | 'delete' | 'upload' | 'download' | 'run' | ...
 *
 * 前端从 code 推断 module，避免给 sys_permission 表再加一列 module 字段。
 * 多语言 module 显示名走 i18n key system.permModule.{module}，回退到 code 原文。
 */

import type { MenuNode } from '@/types';

export interface PermissionLike {
  id?: number;
  code: string;
  name?: string;
  type?: string;
}

export function moduleOf(code: string): string {
  const idx = code.indexOf(':');
  return idx > 0 ? code.slice(0, idx) : '_';
}

export function actionOf(code: string): string {
  const parts = code.split(':');
  return parts[parts.length - 1] || code;
}

/** 角色列表展示 / 编辑器分组顺序：按业务重要性排序 */
export const MODULE_ORDER = [
  'system',
  'invoice',
  'data',
  'documents',
  'dict',
  'design',
  'todo',
  'parser',
  'ocr',
  'setting',
  'file',
] as const;

/**
 * 把扁平的 permissions 按 module 分组，并按 MODULE_ORDER 排序。
 * 返回 [{name: 'system', perms: PermissionLike[]}, ...]
 */
export function groupByModule<T extends PermissionLike>(perms: T[]): { name: string; perms: T[] }[] {
  const groups = new Map<string, T[]>();
  for (const p of perms) {
    const m = moduleOf(p.code);
    if (!groups.has(m)) groups.set(m, []);
    groups.get(m)!.push(p);
  }
  // 先按 MODULE_ORDER 输出已知 module，再追加未列出的（防御未来加新 module）
  const ordered: string[] = [];
  for (const m of MODULE_ORDER) if (groups.has(m)) ordered.push(m);
  for (const m of groups.keys()) if (!ordered.includes(m)) ordered.push(m);
  return ordered.map(name => ({ name, perms: groups.get(name)! }));
}

/** Element Plus icon 名 → 用于按 module 装饰；与 IconPicker 兼容 */
export const MODULE_ICON: Record<string, string> = {
  system: 'Setting',
  invoice: 'Document',
  data: 'Coin',
  documents: 'Folder',
  dict: 'Collection',
  ocr: 'MagicStick',
  parser: 'MagicStick',
  design: 'Brush',
  setting: 'Tools',
  file: 'FolderOpened',
  todo: 'List',
};

/**
 * 顶级 DIR id → 该 DIR 拥有的 perm code 第一段前缀清单。
 * - DIR 自带 `permission` 列时按 `moduleOf(perm)` 自动归集；
 * - DIR 没有 `permission` 或跨多模块时靠下表手工指定兜底。
 * - 新增顶级 DIR（不是 1:1 映射到 module 前缀）时在此追加。
 */
const TOP_DIR_MODULE_HINTS: Record<number, string[]> = {
  10: ['invoice'], // Invoice DIR（无 permission 列，children 也没 perm）
  20: ['system', 'ocr', 'setting', 'file'], // System DIR（多个子模块混合）
};

/** 顶级 DIR id → permModule i18n key 段（用于 t(`system.permModule.${key}`)） */
const TOP_DIR_MODULE_KEY: Record<number, string> = {
  10: 'invoice',
  20: 'system',
  30: 'design',
  40: 'data',
  50: 'documents',
  60: 'todo',
};

export interface TopMenuGroup {
  /** 顶级菜单 id；0 = orphan bucket（无 DIR 归属的 perm） */
  id: number;
  /** admin 设置的菜单名（DB sys_menu.name），fallback 给 i18n key 失败时用 */
  name: string;
  /** sys_menu.path（如 '/documents'） */
  path: string;
  /** 排序权重 */
  sort: number;
  /** i18n key 段（`system.permModule.${moduleKey}`） */
  moduleKey: string;
  /** 该角色在此 DIR 下拥有的 perm 列表（顺序：按 moduleOf 命中先后保留） */
  perms: PermissionLike[];
}

/**
 * 把扁平的 perm 列表按「顶级 DIR」归类。
 *
 * @param perms 角色拥有的 perm 列表（已展开成 PermissionLike）
 * @param menus sys_menu flat 列表（含 parent_id / permission / type / sort）
 * @returns 按顶级 DIR sort 排序的分组 + orphan bucket（id=0）排在最后
 *
 * 算法：
 * 1. 从 menus 抽 `type='DIR' && parent_id=0` 的顶级，按 sort 升序
 * 2. 计算每个顶级 DIR 的 module 前缀集合（hints）：
 *    - DIR.permission 非空 → 加 `moduleOf(DIR.permission)`
 *    - DIR.id ∈ TOP_DIR_MODULE_HINTS → 合并 hints
 *    - 子菜单（descendant）permission 非空 → 把 `moduleOf(perm)` 也加入最近祖先顶级 DIR
 * 3. 每个 perm 按 `moduleOf(code)` 命中第一个 hints 非空的 DIR，append 到其 perms
 * 4. 未命中任何 DIR 的 perm → 落进 id=0 的 orphan bucket
 * 5. Documents DIR（path='/documents'）特殊：返回结果保留 moduleKey='documents'，
 *    由调用方二次用 archivePermGroups() 按 slug 拆子行
 */
export function groupByTopMenu<T extends PermissionLike>(
  perms: T[],
  menus: MenuNode[]
): TopMenuGroup[] {
  // 1. 顶级 DIR
  const tops = menus
    .filter(m => m.type === 'DIR' && (m.parentId === 0 || m.parentId == null))
    .sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0));

  // 2. 计算每个顶级 DIR 的 module 前缀集合
  const dirHints = new Map<number, Set<string>>();
  for (const t of tops) dirHints.set(t.id, new Set());

  // 子菜单 → 父菜单递归向上找顶级 DIR
  function topOf(id: number): MenuNode | undefined {
    let cur = menus.find(m => m.id === id);
    while (cur && cur.parentId && cur.parentId !== 0) {
      cur = menus.find(m => m.id === cur!.parentId);
    }
    return cur;
  }

  // DIR 自带 permission
  for (const t of tops) {
    if (t.permission) dirHints.get(t.id)!.add(moduleOf(t.permission));
  }
  // 兜底 hints
  for (const t of tops) {
    const hints = TOP_DIR_MODULE_HINTS[t.id];
    if (hints) for (const h of hints) dirHints.get(t.id)!.add(h);
  }
  // 子菜单 permission → 自动归集到祖先顶级 DIR
  for (const m of menus) {
    if (!m.permission) continue;
    if (tops.some(t => t.id === m.id)) continue;
    const top = topOf(m.id);
    if (top) dirHints.get(top.id)!.add(moduleOf(m.permission));
  }

  // 3-4. 分配 perms
  const buckets = new Map<number, PermissionLike[]>();
  for (const t of tops) buckets.set(t.id, []);
  const orphans: PermissionLike[] = [];
  for (const p of perms) {
    const mod = moduleOf(p.code);
    let assigned = false;
    for (const t of tops) {
      if (dirHints.get(t.id)!.has(mod)) {
        buckets.get(t.id)!.push(p);
        assigned = true;
        break;
      }
    }
    if (!assigned) orphans.push(p);
  }

  // 5. 输出
  const out: TopMenuGroup[] = tops.map(t => ({
    id: t.id,
    name: t.name,
    path: t.path || '',
    sort: t.sort ?? 0,
    moduleKey: TOP_DIR_MODULE_KEY[t.id] || t.name,
    perms: buckets.get(t.id)!,
  }));
  if (orphans.length) {
    out.push({
      id: 0,
      name: '_other',
      path: '',
      sort: Number.MAX_SAFE_INTEGER,
      moduleKey: '_',
      perms: orphans,
    });
  }
  return out;
}
