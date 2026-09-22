package com.anomalyco.fapiao.web.dto.i18n;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class I18nImportResult {
    public int inserted = 0;
    public int updated = 0;
    public int skipped = 0;
    public List<String> errors = new ArrayList<>();

    public void addError(String msg) { errors.add(msg); }
    public void merge(I18nImportResult other) {
        this.inserted += other.inserted;
        this.updated += other.updated;
        this.skipped += other.skipped;
        this.errors.addAll(other.errors);
    }
}