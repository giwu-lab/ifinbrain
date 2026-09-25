package com.anomalyco.ifinbrain.web.documents.dto;

import com.anomalyco.ifinbrain.web.documents.entity.DocumentRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DocumentDto {

    private Long id;
    private Long categoryId;
    private String name;
    private String storageKey;
    /** 存储后端快照：'local' | 's3'。前端按此判断是否走 302 presigned 或后端流式 */
    private String storageType;
    private String originalFilename;
    private Long sizeBytes;
    private String sha256;
    private String mimeType;
    private Long uploaderId;
    /** 冗余字段，避免前端列表 join user 表 */
    private String uploaderName;
    private String remark;
    private Short status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DocumentDto fromEntity(DocumentRecord e) {
        return DocumentDto.builder()
                .id(e.getId())
                .categoryId(e.getCategoryId())
                .name(e.getName())
                .storageKey(e.getStorageKey())
                .storageType(e.getStorageType())
                .originalFilename(e.getOriginalFilename())
                .sizeBytes(e.getSizeBytes())
                .sha256(e.getSha256())
                .mimeType(e.getMimeType())
                .uploaderId(e.getUploaderId())
                .uploaderName(e.getUploaderName())
                .remark(e.getRemark())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
