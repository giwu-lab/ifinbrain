package com.anomalyco.fapiao.web.dto.dict;

import com.anomalyco.fapiao.web.entity.SysDictItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DictItemDto {
    private Long id;
    private Long dictId;
    private Long parentId;
    private String code;
    private String label;
    private String value;
    private Integer sort;
    private Short status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DictItemDto from(SysDictItem it) {
        return DictItemDto.builder()
                .id(it.getId())
                .dictId(it.getDictId())
                .parentId(it.getParentId())
                .code(it.getCode())
                .label(it.getLabel())
                .value(it.getValue())
                .sort(it.getSort())
                .status(it.getStatus())
                .createdAt(it.getCreatedAt())
                .updatedAt(it.getUpdatedAt())
                .build();
    }
}