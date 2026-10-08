package com.anomalyco.ifinbrain.web.report.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 设计器「试跑」请求 —— **按 queryId 直接取数，不落库**。
 *
 * <p>为什么要有这个独立端点，而不是让设计器先 create 再 run：
 * 设计阶段本来就要反复试参数、反复看列宽合不合适。如果预览必须先保存，
 * 一次调试就会在 {@code sys_report} 里堆一条垃圾草稿（试十次就是十条），
 * 而这些草稿既没人记得是什么，用户还不敢删 —— 怕删掉自己"好像在弄的东西"。
 *
 * <p>所以预览与保存彻底解耦：{@link ReportRunReq} 按报表 id 取数（虚拟滚动用，
 * 有 max_rows / pageSize 等报表级护栏），本 DTO 按 queryId 取数（设计器用，
 * 固定小 size、只取第一页、不产生任何持久化副作用）。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReportPreviewReq {

    /** 要试跑的查询；必须存在且启用 */
    @NotNull(message = "请先选择数据源查询")
    private Long queryId;

    /** 查询头参数值；name 必须是该 query.params_schema 里声明过的（Service 内白名单过滤） */
    private Map<String, String> params;

    /** 试跑取多少行；不传默认 50，上限受 QueryExecutor.MAX_PAGE_SIZE 夹紧 */
    @Min(value = 1, message = "size 最小 1")
    @Max(value = 2000, message = "size 最大 2000")
    private Integer size;
}
