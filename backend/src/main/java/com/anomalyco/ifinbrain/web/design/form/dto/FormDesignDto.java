package com.anomalyco.ifinbrain.web.design.form.dto;

import com.anomalyco.ifinbrain.web.design.form.entity.SysFormDesign;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormDesignDto {

    private Long id;
    private String code;
    private String name;
    private String description;
    /** 字段列表（解析自 fields_json） */
    private java.util.List<FormFieldSchema> fields;
    private Short status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 解析后的 fields_json（前端可见） */
    private String fieldsJson;

    public static FormDesignDto from(SysFormDesign e, java.util.List<FormFieldSchema> fields) {
        return FormDesignDto.builder()
                .id(e.getId())
                .code(e.getCode())
                .name(e.getName())
                .description(e.getDescription())
                .fieldsJson(e.getFieldsJson())
                .fields(fields)
                .status(e.getStatus())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}