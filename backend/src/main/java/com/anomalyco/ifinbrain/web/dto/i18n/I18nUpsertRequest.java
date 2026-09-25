package com.anomalyco.ifinbrain.web.dto.i18n;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class I18nUpsertRequest {

    @NotBlank
    private String namespace;

    @NotBlank
    private String code;

    @NotBlank
    private String valueJson;
}