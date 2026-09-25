package com.anomalyco.ifinbrain.web.documents.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DocumentRenameReq {

    @NotBlank
    @Size(max = 255)
    private String name;
}
