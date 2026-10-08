package com.anomalyco.ifinbrain.web.common;

import lombok.Data;

import java.util.List;

/**
 * 分页返回信封：统一 {@code { items, total, page, size }}。
 *
 * <p>不用 {@code org.springframework.data.domain.Page} 直接序列化：Spring 的 Page 实现
 * 每次迭代都可能触发 lazy load，序列化时 JSON 结构还会随版本漂移。这里显式收敛成
 * 四个字段，前端契约稳定，后端换实现不影响前端。
 *
 * <p>字段名与 {@code UserController.list} 的历史 Map 写法保持一致，
 * 所以前端 {@code PageResult<T>} 可以跨模块复用同一套分页栏。
 */
@Data
public class PageResp<T> {

    /** 当前页数据（已按 page/size 截断，永远不会是全表） */
    private List<T> items;

    /** 满足条件的总条数，供 el-pagination 计算总页数 */
    private long total;

    /** 当前页码，从 1 开始（回显用，不要用 0 基） */
    private int page;

    /** 每页条数，回显用 */
    private int size;

    public static <T> PageResp<T> of(List<T> items, long total, int page, int size) {
        PageResp<T> r = new PageResp<>();
        r.items = items;
        r.total = total;
        r.page = page;
        r.size = size;
        return r;
    }

    /** 从 Spring Data Page 转换；page/size 用安全值回显，避免与实际请求不一致 */
    public static <T> PageResp<T> of(org.springframework.data.domain.Page<T> page, PageReq req) {
        return of(page.getContent(), page.getTotalElements(), req.safePage(), req.safeSize());
    }
}