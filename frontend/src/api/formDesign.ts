import { http } from './http';

/** 表单字段类型（13 种，与后端 FieldType 同步） */
export type FieldType =
  | 'TEXT' | 'TEXTAREA' | 'NUMBER'
  | 'SELECT' | 'RADIO' | 'CHECKBOX'
  | 'DATE' | 'DATETIME'
  | 'USER' | 'DEPT'
  | 'FILE' | 'RICH_TEXT' | 'DIVIDER';

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

/** 字段类型枚举的展示元数据（前端 palette / props 使用） */
export const FIELD_TYPE_META: Record<FieldType, { icon: string; label: string; category: string }> = {
  TEXT:       { icon: 'EditPen',         label: 'Single Line',     category: 'input' },
  TEXTAREA:   { icon: 'Document',        label: 'Multi Line',      category: 'input' },
  NUMBER:     { icon: 'Histogram',       label: 'Number',          category: 'input' },
  SELECT:     { icon: 'Menu',            label: 'Dropdown',        category: 'choice' },
  RADIO:      { icon: 'CircleCheck',     label: 'Radio',           category: 'choice' },
  CHECKBOX:   { icon: 'Select',          label: 'Checkbox',        category: 'choice' },
  DATE:       { icon: 'Calendar',        label: 'Date',            category: 'date' },
  DATETIME:   { icon: 'Timer',           label: 'DateTime',        category: 'date' },
  USER:       { icon: 'User',            label: 'User',            category: 'pick' },
  DEPT:       { icon: 'OfficeBuilding',  label: 'Department',      category: 'pick' },
  FILE:       { icon: 'UploadFilled',    label: 'File Upload',     category: 'special' },
  RICH_TEXT:  { icon: 'Memo',            label: 'Rich Text',       category: 'special' },
  DIVIDER:    { icon: 'Minus',           label: 'Divider',         category: 'layout' },
};

export const FIELD_TYPE_LIST: FieldType[] = [
  'TEXT', 'TEXTAREA', 'NUMBER',
  'SELECT', 'RADIO', 'CHECKBOX',
  'DATE', 'DATETIME',
  'USER', 'DEPT',
  'FILE', 'RICH_TEXT', 'DIVIDER',
];