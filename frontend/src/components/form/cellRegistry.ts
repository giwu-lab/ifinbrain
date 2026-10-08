import type { FieldType } from '@/api/formDesign';
import TextField from './fields/TextField.vue';
import TextareaField from './fields/TextareaField.vue';
import NumberField from './fields/NumberField.vue';
import SelectField from './fields/SelectField.vue';
import RadioField from './fields/RadioField.vue';
import CheckboxField from './fields/CheckboxField.vue';
import DateField from './fields/DateField.vue';
import DatetimeField from './fields/DatetimeField.vue';
import UserField from './fields/UserField.vue';
import DeptField from './fields/DeptField.vue';
import FileField from './fields/FileField.vue';
import RichTextField from './fields/RichTextField.vue';

/**
 * 表格单元格可用的控件 —— 12 种，不含 TABLE / DIVIDER。
 *
 * <p>与后端 {@code FieldType.usableAsColumn()} 保持同一口径：表格不能嵌套表格，
 * 分割线是整块布局元素放进单元格没有意义。
 *
 * <p>刻意与 {@code fieldRegistry.ts} 分成两个文件：fieldRegistry 要 import TableField，
 * 而 TableField 又要用本表，若合成一个文件就会形成循环依赖（Vue SFC 下容易触发 TDZ）。
 */
const CELL_COMPONENTS: Record<string, any> = {
  TEXT: TextField,
  TEXTAREA: TextareaField,
  NUMBER: NumberField,
  SELECT: SelectField,
  RADIO: RadioField,
  CHECKBOX: CheckboxField,
  DATE: DateField,
  DATETIME: DatetimeField,
  USER: UserField,
  DEPT: DeptField,
  FILE: FileField,
  RICH_TEXT: RichTextField,
};

export function resolveCell(type?: FieldType): any {
  return (type && CELL_COMPONENTS[type]) || TextField;
}
