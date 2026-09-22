package com.anomalyco.fapiao.web.dto.i18n;

import com.anomalyco.fapiao.web.entity.SysI18nMessage;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class I18nMessageDto {
    private Long id;
    private String namespace;
    private String code;
    private String valueJson;
    private LocalDateTime updatedAt;

    public static I18nMessageDto from(SysI18nMessage m) {
        return I18nMessageDto.builder()
                .id(m.getId())
                .namespace(m.getNamespace())
                .code(m.getCode())
                .valueJson(m.getValueJson())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}