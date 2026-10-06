package com.anomalyco.ifinbrain.web.todo.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.todo.dto.TodoTagDto;
import com.anomalyco.ifinbrain.web.todo.service.TodoTagService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 任务标签接口。
 *
 * <p>前端 {@code api/todo.ts} 一直在调这组接口，但 V43 字典化重构时 Controller 没有补上，
 * 请求落到静态资源处理器变成 404，再被 GlobalExceptionHandler 的兜底分支翻成
 * 「服务器内部错误」—— 表现为「一进任务管理页就报错」。这里补齐。
 *
 * <p>权限：读用 todo:task:view，写用 todo:task:update。标签是任务表单的数据源而非
 * 通用字典管理，所以不套 dict:update —— 否则只有 todo 权限的用户就没法给任务打标签。
 * 副作用（可接受的既定行为）：这些条目同时也能在「系统设置 → 字典管理」里被管理。
 */
@RestController
@RequestMapping("/api/todo/tags")
@RequiredArgsConstructor
public class TodoTagController {

    private final TodoTagService tagService;

    /** 标签下拉数据源。前端 el-select 的 value 用标签名，不是 id。 */
    @GetMapping
    public Result<List<TodoTagDto>> list() {
        return Result.ok(tagService.list());
    }

    /** 按名 upsert（存在则返回原 id，不存在则新建），返回 dict_item_id。 */
    @PostMapping("/upsert")
    public Result<Long> upsert(@RequestBody Map<String, String> body) {
        String name = body == null ? null : body.get("name");
        if (name == null || name.isBlank()) {
            return Result.fail(400, "标签不能为空");
        }
        return Result.ok(tagService.upsertIdByName(name));
    }

    /** 删除标签。仍被任务引用时返回 409。 */
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        tagService.remove(id);
        return Result.ok();
    }
}
