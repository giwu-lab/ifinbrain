/**
 * 表格「绑定查询 → 自动匹配字段」的选择性同步规则。
 *
 * 为什么要单独一个模块：同步规则里有几条反直觉的取舍，值得能脱离组件单独测。
 *
 * 核心问题：设计者在属性面板上手调过表格列（改了标题、设了宽度、勾了必填、
 * 给单元格配了下拉选项），然后他去改了查询（加了一列、删了一列、改了名）。
 * 这时候表格列该怎么办？
 *
 * 朴素做法是**整表替换** —— 结果设计者半天的手工调整全没了。
 * 另一个极端是**完全不动** —— 那查询改了列也对不上，还得自己一个个加。
 *
 * 这里取中间：**以查询为准增删列，以人工为准保留列的内容**。
 *   - 查询新增的列  → 按自动匹配补进来（key/标题/类型都来自查询）
 *   - 查询删掉的列  → 跟着删（否则表里永远留着一列查不到数据的死列）
 *   - 两边都有的列  → **整列原样保留**，人工调过的一律不动
 *   - 列的顺序     → 以查询为准（查询是数据源，顺序跟着它最不容易懵）
 *
 * 注意「两边都有的列原样保留」意味着：查询把这一列改名了，表格列标题不会被跟着改。
 * 这是刻意的 —— 标题是人话，查询的列名是给机器看的，两者本来就常常不一样，
 * 系统擅自覆盖人写过的字才是真的坑。要改标题用户自己改。
 */

import type { FieldType, FormFieldColumn, QueryColumn } from '@/api/formDesign';

/**
 * 查询列的推测类型 → 单元格控件类型。
 *
 * boolean / unknown / string 都落 TEXT：布尔列不是不能做成单选，但
 * select/radio/checkbox 列必须配 options，而「是/否」这种文案要走 i18n，
 * 在这个纯函数里造不出来。留给用户在属性面板里改，比塞一个写死的文案强。
 */
export function fieldTypeOfQueryColumn(type?: string | null): FieldType {
  switch ((type || '').toLowerCase()) {
    case 'number':
      return 'NUMBER';
    case 'date':
      return 'DATE';
    case 'datetime':
      return 'DATETIME';
    default:
      return 'TEXT';
  }
}

/** 单个查询列 → 表格列定义（自动匹配的初始值，人工可再调） */
export function columnFromQuery(col: QueryColumn): FormFieldColumn {
  const key = (col.key || '').trim();
  return {
    key,
    label: (col.label || key).trim(),
    type: fieldTypeOfQueryColumn(col.type),
    width: col.width ?? undefined,
  };
}

/** 同步结果 + 变更摘要（UI 要把「加了 2 列、删了 1 列」告诉用户，不能悄悄改） */
export interface TableColumnMergeResult {
  /** 同步后的列（顺序以查询为准） */
  columns: FormFieldColumn[];
  /** 本次新增的列 key */
  added: string[];
  /** 本次删除的列 key */
  removed: string[];
  /** 两侧都有、原样保留的列 key */
  kept: string[];
}

/**
 * 选择性同步：把已有列按查询的最新列做增删调整，幸存列的人工内容不动。
 *
 * @param existing 当前表格已配置的列（可能为 undefined）
 * @param incoming 查询返回的列（允许有重复 key / 空 key，这里会兜住）
 */
export function mergeTableColumns(
  existing: FormFieldColumn[] | undefined,
  incoming: QueryColumn[],
): TableColumnMergeResult {
  const oldByKey = new Map<string, FormFieldColumn>();
  for (const c of existing ?? []) {
    const k = (c.key || '').trim();
    // 空 key 的列没法参与匹配，直接丢掉（留着只会让同步永远对不上）
    if (k && !oldByKey.has(k)) oldByKey.set(k, c);
  }

  const columns: FormFieldColumn[] = [];
  const added: string[] = [];
  const kept: string[] = [];
  const seen = new Set<string>();

  for (const col of incoming ?? []) {
    const k = (col.key || '').trim();
    if (!k || seen.has(k)) continue; // 空 key / 重复列名：后端 validateColumns 不接受，先滤掉
    seen.add(k);

    const old = oldByKey.get(k);
    if (old) {
      columns.push(old); // 原样保留：width/required/options/自定义标题全不动
      kept.push(k);
    } else {
      columns.push(columnFromQuery(col));
      added.push(k);
    }
  }

  const removed = [...oldByKey.keys()].filter((k) => !seen.has(k));

  return { columns, added, removed, kept };
}

/**
 * 当前列是否已经和查询对得上 —— 用来判断「要不要真的同步」。
 *
 * **只比 key 集合**，不比标题和类型。这不是偷懒，是被 merge 的语义逼出来的：
 * merge 遇到 key 相同的列是原样保留的，也就是说标题和类型本来就归用户管
 * （把 string 列改成下拉是有意的设计，不是「没对上」）。
 * 如果这里把标题/类型也算进来，就会出现
 * 「判定要同步 → 同步完判定还是没对上」的来回空转，每次打开设计器都白标一次「未保存」。
 *
 * 没有这个判断的话，打开设计器就会因为自动同步把 fields 判成脏，
 * 用户什么都没改却看到「未保存」标记。
 */
export function tableColumnsInSync(
  columns: FormFieldColumn[] | undefined,
  incoming: QueryColumn[],
): boolean {
  // 去重 + 去空必须和 mergeTableColumns 用同一套口径：
  // 判定说「要同步」而 merge 实际什么也没改，就会变成无意义的空转。
  const uniq = (keys: string[]) => [...new Set(keys)];
  const mine = uniq((columns ?? []).map((c) => (c.key || '').trim()).filter((k) => k));
  const theirs = uniq((incoming ?? []).map((col) => (col.key || '').trim()).filter((k) => k));

  if (mine.length !== theirs.length) return false;
  return mine.every((k, i) => k === theirs[i]);
}