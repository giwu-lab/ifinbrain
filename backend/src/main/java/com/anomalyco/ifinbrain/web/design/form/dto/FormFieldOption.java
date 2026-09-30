package com.anomalyco.ifinbrain.web.design.form.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 选项条目（select / radio / checkbox 字段使用） */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormFieldOption {
    /** 显示文本 */
    private String label;
    /** 值（字符串；多选用 "," 分隔） */
    private String value;
    /** 是否禁用 */
    private Boolean disabled;
}