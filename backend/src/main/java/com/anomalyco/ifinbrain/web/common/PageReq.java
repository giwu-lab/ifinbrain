package com.anomalyco.ifinbrain.web.common;

import lombok.Data;

@Data
public class PageReq {
    /** page-size 上限：挡住 size=100000 这种把整表拉进堆内存的请求 */
    public static final int MAX_SIZE = 200;

    /** 兜底每页条数：size 传 0 / 负数时用它，而不是让 DB 报 IllegalArgumentException */
    public static final int DEFAULT_SIZE = 20;

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
        return Math.max(0, (safePage() - 1) * safeSize());
    }

    /** 页码归一化到 1..∞：page 传 0 / 负数时按第 1 页处理 */
    public int safePage() {
        return Math.max(1, page);
    }

    /**
     * 每页条数归一化到 1..{@link #MAX_SIZE}。
     *
     * <p>size ≤ 0 会让 {@code PageRequest.of} 直接抛 {@code IllegalArgumentException} → 500，
     * 与其让一次手滑的请求把接口打成 5xx，不如夹回默认值。
     */
    public int safeSize() {
        if (size <= 0) return DEFAULT_SIZE;
        return Math.min(size, MAX_SIZE);
    }

    public org.springframework.data.domain.PageRequest toPageable() {
        return toPageable(org.springframework.data.domain.Sort.unsorted());
    }

    /**
     * 带默认排序的分页。
     *
     * <p>各列表页原来的「全量 + findAllByOrderByXxx」写法其实已经把排序写死在 Repository 方法名里；
     * 改成 Pageable 后排序要显式传进来，否则 Page 内容顺序由主键兜底，与改版前不一致。
     * {@code defaultSort} 就是原来的那个排序。
     */
    public org.springframework.data.domain.PageRequest toPageable(org.springframework.data.domain.Sort defaultSort) {
        org.springframework.data.domain.Sort sort = defaultSort;
        if (sortBy != null && !sortBy.isBlank()) {
            sort = "desc".equalsIgnoreCase(sortDir)
                    ? org.springframework.data.domain.Sort.by(sortBy).descending()
                    : org.springframework.data.domain.Sort.by(sortBy).ascending();
        }
        return org.springframework.data.domain.PageRequest.of(safePage() - 1, safeSize(), sort);
    }
}