/**
 * 实体级授权对话框的共享类型。
 *
 * <p>单独成文件的原因：{@code PermMatrixDialog.vue} 用的是 `<script setup>`，
 * 而 `<script setup>` **不允许** 具名 export（写了会让整个模块在类型层解析失败，
 * 导入方直接报 TS2307）。所以凡是多个地方要用的类型都得挪出来。
 */

export interface PermVerbSpec {
  /** 后端返回体里的键，如 view / design / publish / delete */
  key: string;
  /** i18n 文案（已解析好的字符串） */
  label: string;
}

/** 后端授权接口的返回体：{ view: [roleId], design: [...], ... } */
export type PermGrants = Record<string, number[]>;