package com.anomalyco.ifinbrain.web.todo.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.todo.dto.TodoAttachmentDto;
import com.anomalyco.ifinbrain.web.todo.service.TodoAttachmentService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/todo")
@RequiredArgsConstructor
public class TodoAttachmentController {

    private final TodoAttachmentService service;

    /** 上传附件到指定任务。20MB 硬限。 */
    @PostMapping("/tasks/{taskId}/attachments")
    public Result<TodoAttachmentDto> upload(@PathVariable Long taskId,
                                            @RequestParam("file") MultipartFile file) {
        return Result.ok(service.upload(taskId, file));
    }

    /** 任务的附件列表（按创建时间倒序）。 */
    @GetMapping("/tasks/{taskId}/attachments")
    public Result<List<TodoAttachmentDto>> list(@PathVariable Long taskId) {
        return Result.ok(service.list(taskId));
    }

    /** 删除附件（soft-delete + 清理 storage）。 */
    @DeleteMapping("/attachments/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /**
     * 下载 / 内嵌预览附件。
     * @param inline true=浏览器内嵌（image/pdf），false=强制下载。
     */
    @GetMapping("/attachments/{id}/raw")
    public void raw(@PathVariable Long id,
                    @RequestParam(value = "inline", defaultValue = "false") boolean inline,
                    HttpServletResponse response) {
        service.streamRaw(id, !inline, response);
    }
}