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
import TableField from './fields/TableField.vue';
import DividerField from './fields/DividerField.vue';

/**
 * 字段类型 → 控件组件的唯一映射（14 种）。
 *
 * <p>原先 FormRenderer 和 FormCanvas 各维护一份同样的 map，加新字段类型时漏改一处
 * 就会出现「运行时空白、设计画布正常」这类难查的问题。这里收敛成单一来源，
 * 两处都从这里取；单元格用的子集见 {@code cellRegistry.ts}。
 */
const FIELD_COMPONENTS: Record<FieldType, any> = {
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
  TABLE: TableField,
  DIVIDER: DividerField,
};

export function resolveField(type: FieldType): any {
  return FIELD_COMPONENTS[type] || TextField;
}
