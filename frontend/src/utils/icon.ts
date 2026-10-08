import * as ElIcons from '@element-plus/icons-vue';
import type { Component } from 'vue';

/**
 * 统一的 Element Plus 图标解析器（sys_menu.icon → 组件）。
 *
 * 背景：sys_menu.icon 是自由文本，早期种子数据（V2/V6/V10）存的是小写短横线写法
 * （'odometer' / 'document' / 'magic-stick' / 'database'），而各 layout 原先直接
 * `ElIcons[name]` 精确查表 —— Element Plus 的导出名全是 PascalCase（Odometer /
 * Document / MagicStick），小写一律查不到，于是全部 fallback 成 Document。
 * 结果就是「仪表盘 / 发票管理 / 数据管理 / 系统设置」四个顶级入口渲染出同一个图标。
 *
 * 这里做三级兜底，让历史数据与 admin 在「菜单管理」里手填的各种写法都能命中：
 *   1. 精确匹配（'Odometer'）
 *   2. kebab / snake / 空格 / 点 / 斜杠 → PascalCase（'magic-stick' → 'MagicStick'）
 *   3. 全小写索引匹配（'magicstick' → 'MagicStick'）
 * 全都不中才回落到 fallback。
 */
type IconComp = Component;

const REGISTRY = ElIcons as unknown as Record<string, IconComp>;

/** 排除 install / default 这类非图标导出，与 IconPicker 的过滤口径保持一致。 */
const ICON_KEYS = Object.keys(REGISTRY).filter(
  (k) => k !== 'default' && k !== 'install' && typeof REGISTRY[k] !== 'function'
);

/** 全部可用图标名（PascalCase），供 IconPicker 的选择面板遍历。 */
export const EL_ICON_NAMES: readonly string[] = ICON_KEYS;

/** 小写 → 规范 key 的反查表，首次调用时构建一次。 */
const LOWER_INDEX: Record<string, string> = ICON_KEYS.reduce<Record<string, string>>((acc, k) => {
  acc[k.toLowerCase()] = k;
  return acc;
}, {});

/** 'magic-stick' / 'magic_stick' / 'magic stick' / 'magic.stick' → 'MagicStick' */
function toPascalCase(raw: string): string {
  return raw
    .split(/[-_\s./]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join('');
}

/**
 * @param name    sys_menu.icon 原始值，可空
 * @param fallback 查不到时的兜底组件，默认 Document
 */
export function resolveElIcon(name?: string | null, fallback: IconComp = ElIcons.Document): IconComp {
  const raw = (name ?? '').trim();
  if (!raw) return fallback;

  if (REGISTRY[raw]) return REGISTRY[raw];

  const pascal = toPascalCase(raw);
  if (REGISTRY[pascal]) return REGISTRY[pascal];

  const hit = LOWER_INDEX[raw.toLowerCase()];
  return (hit && REGISTRY[hit]) || fallback;
}