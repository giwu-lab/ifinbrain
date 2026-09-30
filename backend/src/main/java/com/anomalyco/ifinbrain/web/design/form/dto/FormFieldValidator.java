package com.anomalyco.ifinbrain.web.design.form.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 字段简单校验 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormFieldValidator {
    /** 最小长度/值（text/textarea = char 数，number = 最小值） */
    private Integer min;
    /** 最大长度/值 */
    private Integer max;
    /** 正则表达式（仅 text/textarea） */
    private String pattern;
    /** 自定义错误文案（可选） */
    private String message;
}