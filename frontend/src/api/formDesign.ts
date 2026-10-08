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