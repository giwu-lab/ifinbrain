package com.anomalyco.ifinbrain.web.dto.dict;

import com.anomalyco.ifinbrain.web.entity.SysDictType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DictTypeDto {
    private Long id;
    private String code;
    private String name;
    private String remark;
    private Short status;
    private Long itemCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DictTypeDto from(SysDictType t, Long itemCount) {
        return DictTypeDto.builder()
                .id(t.getId())
                .code(t.getCode())
                .name(t.getName())
                .remark(t.getRemark())
                .status(t.getStatus())
                .itemCount(itemCount)
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}