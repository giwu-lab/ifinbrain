package com.anomalyco.fapiao.web.dto.dict;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DictImportResult {
    /** dryRun=true 时只解析不写库 */
    private boolean dryRun;
    private int ok;
    private int updated;
    private int skipped;
    private int error;
    private List<String> messages;
}