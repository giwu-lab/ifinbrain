package com.anomalyco.ifinbrain.web.todo.controller;

import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.todo.dto.TodoAssigneeOption;
import com.anomalyco.ifinbrain.web.todo.dto.TodoDashboardItem;
import com.anomalyco.ifinbrain.web.todo.dto.TodoDashboardResponse;
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
}
