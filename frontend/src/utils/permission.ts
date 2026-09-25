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
  'i18n',
  'dict',
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
  i18n: 'ChatLineSquare',
  dict: 'Collection',
  ocr: 'MagicStick',
  setting: 'Tools',
  file: 'FolderOpened',
};
