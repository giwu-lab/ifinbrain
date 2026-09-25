package com.anomalyco.ifinbrain.web.common;

import lombok.Data;

@Data
public class PageReq {
    private int page = 1;
    private int size = 20;
    private String keyword;
    private String seller;
    private String verificationNo;
    private String verifier;
    /** 核销日期查询：起始日期 YYYY-MM-DD（含），与 verificationDateTo 共同构成期间 */
    private String verificationDateFrom;
    /** 核销日期查询：结束日期 YYYY-MM-DD（含） */
    private String verificationDateTo;
    private String sortBy;
    private String sortDir;

    public int getOffset() {
        return Math.max(0, (page - 1) * size);
    }

    public org.springframework.data.domain.PageRequest toPageable() {
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.unsorted();
        if (sortBy != null && !sortBy.isBlank()) {
            sort = "desc".equalsIgnoreCase(sortDir)
                    ? org.springframework.data.domain.Sort.by(sortBy).descending()
                    : org.springframework.data.domain.Sort.by(sortBy).ascending();
        }
        return org.springframework.data.domain.PageRequest.of(Math.max(0, page - 1), size, sort);
    }
}