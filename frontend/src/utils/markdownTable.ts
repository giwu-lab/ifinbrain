/**
 * Markdown 表格的解析与判定 —— live preview 渲染与回车补全共用。
 *
 * 单独抽出来是因为两处都要用，且必须**同口径**：
 * 渲染时认得什么是表格，输入时就得认得什么时候该补分隔行，
 * 否则会出现"自动补出来的行渲染器却不认"的诡异错位。
 *
 * <p><b>分隔行是硬要求</b>：GFM 规定第二个分隔行每格只能由 `-`、`:`、空格组成。
 * 写成 `| ---ASDF- |` 就只是普通段落，所有渲染器（Typora / marked / GitHub）一致。
 * 这是最容易手滑的一步，所以编辑器会在回车时替用户补上。
 */

/** 去掉首尾竖线后按 | 切分，逐格 trim。 */
export function splitTableRow(line: string): string[] {
  let s = line.trim();
  if (s.startsWith('|')) s = s.slice(1);
  if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1);
  return s.split('|').map((c) => c.trim());
}

/** 该行是否是 GFM 分隔行（`| --- | :---: | ------ |`）。 */
export function isTableDelimiter(line: string): boolean {
  const cells = splitTableRow(line);
  return cells.length > 0 && cells.every((c) => /^:?-+:?$/.test(c));
}

export interface ParsedTable {
  rows: string[][];
  hasHeader: boolean;
}

/** 从源码切出表格，跳过分隔行并记录是否有表头。 */
export function parseTable(source: string): ParsedTable {
  const rows: string[][] = [];
  let hasHeader = false;
  for (const line of source.split('\n')) {
    if (!line.trim()) continue;
    if (isTableDelimiter(line)) {
      hasHeader = true;
      continue;
    }
    rows.push(splitTableRow(line));
  }
  return { rows, hasHeader };
}