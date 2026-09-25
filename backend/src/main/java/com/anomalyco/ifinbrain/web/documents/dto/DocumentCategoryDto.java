package com.anomalyco.ifinbrain.web.documents.dto;

import com.anomalyco.ifinbrain.web.documents.entity.DocumentCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DocumentCategoryDto {

    private Long id;
    private String name;
    private String slug;
    private String icon;
    private Integer sort;
    private Short status;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 该类目下档案数（status=1），由 service 层填充。0 表示无档案。 */
    private Long fileCount;

    public static DocumentCategoryDto fromEntity(DocumentCategory e) {
        return DocumentCategoryDto.builder()
                .id(e.getId())
                .name(e.getName())
                .slug(e.getSlug())
                .icon(e.getIcon())
                .sort(e.getSort())
                .status(e.getStatus())
                .remark(e.getRemark())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    public static DocumentCategoryDto fromEntity(DocumentCategory e, long fileCount) {
        DocumentCategoryDto dto = fromEntity(e);
        dto.setFileCount(fileCount);
        return dto;
    }
}
