package com.anomalyco.ifinbrain.web.todo.controller;

import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.todo.dto.TodoAssigneeOption;
import com.anomalyco.ifinbrain.web.todo.dto.TodoDashboardItem;
import com.anomalyco.ifinbrain.web.todo.dto.TodoDashboardResponse;
import com.anomalyco.ifinbrain.web.todo.dto.TodoInsightResponse;
import com.anomalyco.ifinbrain.web.todo.dto.TodoQuadrantResponse;
import com.anomalyco.ifinbrain.web.todo.dto.TodoTaskCreateReq;
import com.anomalyco.ifinbrain.web.todo.dto.TodoTaskDto;
import com.anomalyco.ifinbrain.web.todo.dto.TodoTaskUpdateReq;
import com.anomalyco.ifinbrain.web.todo.service.TodoTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/todo/tasks")
@RequiredArgsConstructor
public class TodoTaskController {

    private final TodoTaskService service;

    /**
     * 列表 + 筛选（status / priority / keyword）。
     * page/size 复用通用 PageReq，但 priority/status/keyword 是任务领域字段，落在 service 内存过滤。
     * admin (todo:task:view-all) → 看全员；其他人 → 仅看自己的。
     */
    @GetMapping
    public Result<Map<String, Object>> list(@ModelAttribute PageReq req,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String priority) {
        String keyword = req.getKeyword();
        return Result.ok(service.page(req.getPage(), req.getSize(), status, priority, keyword));
    }

    @GetMapping("/{id}")
    public Result<TodoTaskDto> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody TodoTaskCreateReq req) {
        return Result.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody TodoTaskUpdateReq req) {
        service.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/complete")
    public Result<Long> complete(@PathVariable Long id) {
        return Result.ok(service.complete(id));
    }

    /**
     * 直接子任务明细（含已完成）—— 父任务卡片展开子任务时懒加载。
     *
     * <p>只返回直接子级；孙级由前端在展开某条子任务时再调一次本接口。
     * 默认隐藏，所以不在列表接口里内联，避免一屏 20 张卡各带一棵子树。
     */
    @GetMapping("/{id}/children")
    public Result<List<TodoTaskDto>> children(@PathVariable Long id) {
        return Result.ok(service.children(id));
    }

    /** 父任务下拉数据源：当前 assignee 的 PENDING 任务（排除自己）。 */
    @GetMapping("/parent-candidates")
    public Result<List<Map<String, Object>>> parentCandidates(@RequestParam(required = false) Long excludeId) {
        return Result.ok(service.parentCandidates(excludeId));
    }

    /** 执行人选择器数据源：admin 创建/重指派任务时使用；perm 校验由前端 auth.hasPerm('todo:task:assign') 控制 UI 渲染。 */
    @GetMapping("/assignees")
    public Result<List<TodoAssigneeOption>> assignees() {
        return Result.ok(service.assigneeOptions());
    }

    /** 仪表盘 widget + 「我的任务」卡片数据源：最多 N 条<b>本人</b>的 PENDING，按 due_at 排序。
     *  <b>恒按 assigneeId = me 过滤</b>，即使调用方持 todo:task:view-all 也一样 ——
     *  「我的任务」混入别人的任务会让计数失去意义。全员视图请用列表接口 GET /api/todo/tasks。
     *  返回 { items: [...], total: N } —— total 是真实总数（与 items.length 解耦） */
    @GetMapping("/dashboard")
    public Result<TodoDashboardResponse> dashboard(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(service.dashboard(limit));
    }

    /**
     * 四象限视图数据源（仪表盘「我的任务」→ 四象限 tab）。
     *
     * <p>返回 { stats, quadrants:[4 个格子，恒定存在] }。象限归属由
     * priority × dueAt 实时推导（见 TodoQuadrant），<b>不落库</b> —— 今天到期
     * 的任务明天就不再紧急，落库会过期。
     *
     * <p>口径与 /dashboard 一致：只看 assignee = me，view-all 也不混入他人任务。
     */
    @GetMapping("/quadrant")
    public Result<TodoQuadrantResponse> quadrant(@RequestParam(defaultValue = "200") int limit) {
        return Result.ok(service.quadrant(limit));
    }

    /**
     * 「今天」tab 数据源：逾期 + 今日到期的 PENDING 任务，按截止时间升序。
     * 逾期一并返回 —— 今天该做什么，逾期的优先级最高。
     */
    @GetMapping("/today")
    public Result<Map<String, Object>> today(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(service.today(limit));
    }

    /**
     * 我的 PENDING 任务数 —— 顶栏通知红点。
     * 与 flowTask 的 /pending/count 同一形态：一个数字，不返回任务列表。
     */
    @GetMapping("/pending/count")
    public Result<Long> pendingCount() {
        return Result.ok(service.pendingCount());
    }

    /**
     * 洞察 tab 数据源：时间趋势（完成/新增）+ 象限结构 + 行动建议。
     *
     * <p>{@code days} 收敛到 7~90，前端切换 7/30 天。不传则默认 7。
     */
    @GetMapping("/insight")
    public Result<TodoInsightResponse> insight(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(service.insight(days));
    }
}
