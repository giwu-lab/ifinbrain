package com.anomalyco.ifinbrain.web.todo.dto;

import com.anomalyco.ifinbrain.web.todo.entity.TodoAttachment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TodoAttachmentDto {

    private Long id;
    private Long taskId;
    /** 存储后端快照：'local' | 's3'。 */
    private String storageType;
    private String originalFilename;
    private Long sizeBytes;
    /** 人类可读大小，如 "1.2 MB"。 */
    private String sizeLabel;
    private String mimeType;
    private Long uploaderId;
    private String uploaderName;
    private LocalDateTime createdAt;

    public static TodoAttachmentDto fromEntity(TodoAttachment a) {
        return TodoAttachmentDto.builder()
                .id(a.getId())
                .taskId(a.getTaskId())
                .storageType(a.getStorageType())
                .originalFilename(a.getOriginalFilename())
                .sizeBytes(a.getSizeBytes())
                .sizeLabel(formatSize(a.getSizeBytes()))
                .mimeType(a.getMimeType())
                .uploaderId(a.getUploaderId())
                .uploaderName(a.getUploaderName())
                .createdAt(a.getCreatedAt())
                .build();
    }

    private static String formatSize(Long bytes) {
        if (bytes == null || bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format(i == 0 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }
}