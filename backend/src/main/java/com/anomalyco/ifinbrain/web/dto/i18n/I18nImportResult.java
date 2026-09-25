package com.anomalyco.ifinbrain.web.dto.i18n;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class I18nImportResult {
    @Builder.Default
    public int inserted = 0;
    @Builder.Default
    public int updated = 0;
    @Builder.Default
    public int skipped = 0;
    @Builder.Default
    public List<String> errors = new ArrayList<>();

    public void addError(String msg) {
        if (errors == null) errors = new ArrayList<>();
        errors.add(msg);
    }
    public void merge(I18nImportResult other) {
        this.inserted += other.inserted;
        this.updated += other.updated;
        this.skipped += other.skipped;
        if (other.errors != null) {
            if (this.errors == null) this.errors = new ArrayList<>();
            this.errors.addAll(other.errors);
        }
    }
}