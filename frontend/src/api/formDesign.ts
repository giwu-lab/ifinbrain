import { http } from './http';

/** 表单字段类型（14 种，与后端 FieldType 同步） */
export type FieldType =
  | 'TEXT' | 'TEXTAREA' | 'NUMBER'
  | 'SELECT' | 'RADIO' | 'CHECKBOX'
  | 'DATE' | 'DATETIME'
  | 'USER' | 'DEPT'
  | 'FILE' | 'RICH_TEXT' | 'TABLE' | 'DIVIDER';

/** DIVIDER 是整块布局元素，不能作为表格列 —— 与后端 FieldType.usableAsColumn() 同口径 */
export const COLUMN_TYPES: FieldType[] = [
  'TEXT', 'TEXTAREA', 'NUMBER',
  'SELECT', 'RADIO', 'CHECKBOX',
  'DATE', 'DATETIME',
  'USER', 'DEPT',
  'FILE', 'RICH_TEXT',
];

/** 选项条目（select/radio/checkbox 字段） */
export interface FormFieldOption {
  label: string;
  value: string;
  disabled?: boolean;
}

/** 字段简单校验（text/textarea/number） */
export interface FormFieldValidator {
  min?: number;
  max?: number;
  pattern?: string;
  message?: string;
}

/**
 * 表格（TABLE）的一列定义。
 *
 * 运行时值形状：表格字段的值是对象数组，列 key 即行对象的属性名。
 *   "expenseItems": [ { "name": "机票", "amount": 1200 } ]
 */
export interface FormFieldColumn {
  /** 列标识 —— 行对象的属性名；同一张表内唯一 */
  key: string;
  /** 列标题 */
  label: string;
  /** 单元格控件类型；不可为 DIVIDER（见 COLUMN_TYPES） */
  type: FieldType;
  /** 列宽 px；不填自适应 */
  width?: number;
  /** 该列单元格是否必填 —— 仅在表格开启 requireAllCells 时参与校验 */
  required?: boolean;
  /** 选项列表（type 为 SELECT/RADIO/CHECKBOX 时必填） */
  options?: FormFieldOption[];
}

/**
 * 选择类字段的动态选项源 —— 与后端
 * `design/form/dto/FormFieldOptionsSource.java` 一一对应。
 *
 * 与静态 `options` 二选一：配了本项就以查询结果为准。
 */
export interface FormFieldOptionsSource {
  /** 指向 sys_query.id；不内嵌 SQL，查询改了就自动生效 */
  queryId: number;
  /** 查询参数值（对应 sys_query.params_schema[].name） */
  params?: Record<string, string>;
  /** 取哪一列当显示文本（结果集列名） */
  labelField: string;
  /** 取哪一列当值（结果集列名） */
  valueField: string;
  /** 服务端缓存秒数；不填用后端默认 */
  cacheSec?: number;
}

/**
 * 表格的动态列源 —— 与后端 `design/form/dto/FormFieldTableSource.java` 一一对应。
 *
 * 与 optionsSource 同构，区别是：它匹配的是「列」而不是「选项」，并且多一个动态更新开关。
 * 与静态 `columns` 不是覆盖关系，而是**选择性同步**（规则见 utils/form/tableColumns.ts）。
 */
export interface FormFieldTableSource {
  /** 指向 sys_query.id；不内嵌 SQL，查询改了就自动生效 */
  queryId: number;
  /** 查询参数值（对应 sys_query.params_schema[].name） */
  params?: Record<string, string>;
  /**
   * 动态更新：查询列变了是否自动同步表格列。
   *
   * 默认关是有意的 —— 同步会删列，而删列对已填过数据的单子是有影响的，
   * 不该由系统在后台悄悄做。关着也随时能点「立即同步」手动同步一次。
   */
  autoSync?: boolean;
  /** 服务端缓存秒数；不填用后端默认 */
  cacheSec?: number;
}

/**
 * 查询结果集的一列（后端 `QueryResultDto.Column` 的前端形态）。
 *
 * `type` 是服务端推测的类型：string | number | date | datetime | boolean | unknown。
 */
export interface QueryColumn {
  key: string;
  label?: string;
  type?: string;
  width?: number | null;
  align?: string | null;
}

/**
 * 表格显示的查询数据（后端 `FormTableDataDto`）。
 *
 * 行的形状刻意做成「列 key → 值」而不是 BI 那边的下标数组：表格列是设计者可配的
 * （能改顺序、删列），按下标取必然错位。这样也和表格字段本来的值形状一致。
 */
export interface FormTableData {
  columns: FormFieldColumn[];
  rows: Record<string, any>[];
  truncated: boolean;
  rowCount: number;
}

/**
 * 字段在栅格中的位置（24 列栅格，与 el-form 的 span 口径一致）。
 *
 * 缺省表示「还没排版」，加载时由 `utils/form/layout.ts#migrateFields`
 * 按老的 fullWidth 语义自动补齐 —— 补齐后渲染只有一条路径。
 */
export interface FormFieldLayout {
  /** 起始列，0-based */
  x: number;
  /** 起始行，0-based，无上界（表单高度随字段数增长） */
  y: number;
  /** 跨列数，1..24 */
  w: number;
  /** 跨行数，≥1 */
  h: number;
}

/** 字段级样式覆盖。没配的属性走主题默认，不硬编码颜色。 */
export interface FormFieldStyle {
  /** 背景色；'transparent' 表示透出画布底色 */
  background?: string;
  /** 文字颜色 */
  color?: string;
  /** 字号 px */
  fontSize?: number;
  /** label 文字颜色 */
  labelColor?: string;
  /** 标签对齐 */
  labelAlign?: 'left' | 'center' | 'right';
  /** 控件对齐 */
  align?: 'left' | 'center' | 'right';
  /** 内边距 px */
  padding?: number;
  /** 圆角 px */
  radius?: number;
  /** 边框，如 '1px solid #e5e7eb'；'none' 表示不画 */
  border?: string;
  /** 标签宽度 px；配了会切到 left 布局 */
  labelWidth?: number;
}

/** 单个字段 schema */
export interface FormFieldSchema {
  id: string;
  type: FieldType;
  key: string;
  label: string;
  required?: boolean;
  defaultValue?: string;
  placeholder?: string;
  help?: string;
  options?: FormFieldOption[];
  validator?: FormFieldValidator;
  fullWidth?: boolean;
  /** 表格列定义（仅 TABLE） */
  columns?: FormFieldColumn[];
  /** 表格「每行填满必填列」开关（仅 TABLE），与 required 正交 */
  requireAllCells?: boolean;
  /** 动态选项源（SELECT/RADIO/CHECKBOX）；与静态 options 二选一 */
  optionsSource?: FormFieldOptionsSource;
  /** 表格动态列源（仅 TABLE）：列由查询结果集自动匹配，带动态更新开关 */
  tableSource?: FormFieldTableSource;

  /** 栅格位置（可选，加载时自动迁移） */
  layout?: FormFieldLayout;
  /** 样式覆盖（可选） */
  style?: FormFieldStyle;

  // —— 条件逻辑（单表达式，求值为布尔；失败一律按 false 处理）——
  /** 显示条件；为空 = 恒显示。false 时字段不渲染、不参与校验 */
  visibleWhen?: string;
  /** 禁用条件；为空 = 恒可编辑。只挡交互，值仍照常提交 */
  disabledWhen?: string;
  /** 必填条件；为空 = 沿用字段级 required。两者任一为真即必填 */
  requiredWhen?: string;
}

/** 表单级栅格配置 */
export interface FormGridConfig {
  cols: number;
  rowHeight: number;
  gap: number;
}

/** 表单元数据 DTO */
export interface FormDesignItem {
  id: number;
  code: string;
  name: string;
  description?: string;
  fields: FormFieldSchema[];
  fieldsJson?: string;
  status: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface FormDesignCreateBody {
  code: string;
  name: string;
  description?: string;
  fields: FormFieldSchema[];
  status?: number;
}

export interface FormDesignUpdateBody {
  code?: string;
  name?: string;
  description?: string;
  fields?: FormFieldSchema[];
  status?: number;
}

export const formDesignApi = {
  list: () => http.get<any, FormDesignItem[]>('/design/forms'),
  detail: (id: number) => http.get<any, FormDesignItem>(`/design/forms/${id}`),
  detailByCode: (code: string) => http.get<any, FormDesignItem>(`/design/forms/by-code/${code}`),
  create: (body: FormDesignCreateBody) =>
    http.post<any, FormDesignItem>('/design/forms', body),
  update: (id: number, body: FormDesignUpdateBody) =>
    http.put<any, FormDesignItem>(`/design/forms/${id}`, body),
  remove: (id: number) => http.delete<any, null>(`/design/forms/${id}`),
  publish: (id: number) => http.post<any, FormDesignItem>(`/design/forms/${id}/publish`),
  unpublish: (id: number) => http.post<any, FormDesignItem>(`/design/forms/${id}/unpublish`),

  /**
   * 按动态选项源取回选项列表。
   *
   * 入参就是字段 schema 里的 optionsSource 原样 —— 设计期怎么配的，运行时就怎么取，
   * 前端不自己拼参数。权限是 design:form:view（填表人不需要 BI 权限）。
   */
  options: (src: FormFieldOptionsSource) =>
    http.post<any, FormFieldOption[]>('/design/forms/options', src),

  /**
   * 按表格动态列源取回**列元数据**（不返回行）。
   *
   * 表格组件「自动匹配字段」与「立即同步」都靠它：拿到的是这条查询现在有哪些列。
   * 入参同样是字段 schema 里的 tableSource 原样，权限与 /design/forms/options 一致。
   */
  tableColumns: (src: FormFieldTableSource) =>
    http.post<any, QueryColumn[]>('/design/forms/table-columns', src),

  /**
   * 取回查询数据本身，供表格**显示**（绑了查询的表格是展示组件，不是填写组件）。
   *
   * 与 tableColumns 的分工：那边只要列（设计期匹配列用，不值得把行也传回来），
   * 这边给列 + 行（运行期显示用）。
   */
  tableData: (src: FormFieldTableSource) =>
    http.post<any, FormTableData>('/design/forms/table-data', src),
};

/**
 * 字段类型枚举的展示元数据（前端 palette / outline 使用）
 *
 * 显示名统一走 i18n 键 `formDesigner.type.<TYPE>`（见 locales），此处不再重复维护
 * 一份中英文表 —— 那样必然与 locale 不同步。渲染时用 `t(\`formDesigner.type.${type}\`)`。
 */
export const FIELD_TYPE_META: Record<FieldType, { icon: string; category: string }> = {
  TEXT:       { icon: 'EditPen',         category: 'input' },
  TEXTAREA:   { icon: 'Document',        category: 'input' },
  NUMBER:     { icon: 'Histogram',       category: 'input' },
  SELECT:     { icon: 'Menu',            category: 'choice' },
  RADIO:      { icon: 'CircleCheck',     category: 'choice' },
  CHECKBOX:   { icon: 'Select',          category: 'choice' },
  DATE:       { icon: 'Calendar',        category: 'date' },
  DATETIME:   { icon: 'Timer',           category: 'date' },
  USER:       { icon: 'User',            category: 'pick' },
  DEPT:       { icon: 'OfficeBuilding',  category: 'pick' },
  FILE:       { icon: 'UploadFilled',    category: 'special' },
  RICH_TEXT:  { icon: 'Memo',            category: 'special' },
  TABLE:      { icon: 'Grid',            category: 'layout' },
  DIVIDER:    { icon: 'Minus',           category: 'layout' },
};

export const FIELD_TYPE_LIST: FieldType[] = [
  'TEXT', 'TEXTAREA', 'NUMBER',
  'SELECT', 'RADIO', 'CHECKBOX',
  'DATE', 'DATETIME',
  'USER', 'DEPT',
  'FILE', 'RICH_TEXT', 'TABLE', 'DIVIDER',
];