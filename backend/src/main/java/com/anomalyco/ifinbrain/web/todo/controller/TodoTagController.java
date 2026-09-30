package com.anomalyco.ifinbrain.web.todo.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.todo.dto.TodoTagDto;
import com.anomalyco.ifinbrain.web.todo.service.TodoTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/todo/tags")
@RequiredArgsConstructor
public class TodoTagController {

    private final TodoTagService service;

    @GetMapping
    public Result<List<TodoTagDto>> list() {
        return Result.ok(service.list());
    }

    /** 按名 upsert —— 用于任务弹窗里即时建标签。 */
    @PostMapping("/upsert")
    public Result<Long> upsert(@RequestBody Map<String, String> body) {
        return Result.ok(service.upsertByName(body.get("name")));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
