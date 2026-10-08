package com.anomalyco.ifinbrain.web.documents.note.controller;

import com.anomalyco.ifinbrain.web.common.Result;
import com.anomalyco.ifinbrain.web.documents.dto.ArchiveLibraryDto;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentDto;
import com.anomalyco.ifinbrain.web.documents.note.dto.*;
import com.anomalyco.ifinbrain.web.documents.note.service.NoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 笔记 Controller。REST 端点：/api/notes
 *
 * <p>权限校验全在 service 层（perm.require + NoteAclService），
 * controller 只做参数绑定与 Result 信封包装 —— 这是 AGENTS §5 的既定分工。
 */
@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService service;

    /**
     * 列表。
     *
     * @param scope     all / mine / shared / team / library / archived
     * @param keyword   标题 + 正文模糊匹配
     * @param tag       单标签精确匹配
     * @param categoryId 只看挂在某个档案库上的
     * @param onlyArchived / onlyPinned 叠加筛选
     */
    @GetMapping
    public Result<Page<NoteDto>> page(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) String scope,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String tag,
                                      @RequestParam(required = false) Long categoryId,
                                      @RequestParam(required = false) Boolean onlyArchived,
                                      @RequestParam(required = false) Boolean onlyPinned) {
        return Result.ok(service.page(page, size, scope, keyword, tag, categoryId, onlyArchived, onlyPinned));
    }

    /** 列表页 tab 角标。 */
    @GetMapping("/stats")
    public Result<NoteStatsDto> stats() {
        return Result.ok(service.stats());
    }

    /** 共享对话框的候选用户（只启用的）。 */
    @GetMapping("/candidates")
    public Result<List<NoteCandidateDto>> candidates() {
        return Result.ok(service.candidates());
    }

    /** 可关联的档案库（只返当前用户有 view 的）。 */
    @GetMapping("/libraries")
    public Result<List<ArchiveLibraryDto>> libraries() {
        return Result.ok(service.libraries());
    }

    @GetMapping("/{id}")
    public Result<NoteDto> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @PostMapping
    public Result<NoteDto> create(@Valid @RequestBody NoteCreateReq req) {
        return Result.ok(service.create(req));
    }

    /** 更新（带 version 乐观锁，冲突抛 409）。 */
    @PutMapping("/{id}")
    public Result<NoteDto> update(@PathVariable Long id,
                                  @Valid @RequestBody NoteUpdateReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    @PutMapping("/{id}/pin")
    public Result<NoteDto> pin(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return Result.ok(service.setPinned(id, Boolean.TRUE.equals(body.get("pinned"))));
    }

    @PutMapping("/{id}/archive")
    public Result<NoteDto> archive(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return Result.ok(service.setArchived(id, Boolean.TRUE.equals(body.get("archived"))));
    }

    // ---- 共享名单 ----

    @GetMapping("/{id}/shares")
    public Result<List<NoteShareDto>> shares(@PathVariable Long id) {
        return Result.ok(service.listShares(id));
    }

    /** 全量替换：{ items: [{ userId, grantType }] }。 */
    @PutMapping("/{id}/shares")
    public Result<List<NoteShareDto>> replaceShares(@PathVariable Long id,
                                                    @RequestBody NoteShareUpdateReq req) {
        return Result.ok(service.replaceShares(id, req));
    }

    // ---- 版本历史 ----

    @GetMapping("/{id}/revisions")
    public Result<List<NoteRevisionDto>> revisions(@PathVariable Long id) {
        return Result.ok(service.listRevisions(id));
    }

    @GetMapping("/{id}/revisions/{revision}")
    public Result<NoteRevisionDto> revision(@PathVariable Long id, @PathVariable int revision) {
        return Result.ok(service.revision(id, revision));
    }

    /** 回滚到某版本（作为一次普通保存，历史不丢）。 */
    @PostMapping("/{id}/revisions/{revision}/restore")
    public Result<NoteDto> restore(@PathVariable Long id, @PathVariable int revision) {
        return Result.ok(service.restore(id, revision));
    }

    /** 发布为档案库里的 .md 文件。 */
    @PostMapping("/{id}/publish")
    public Result<DocumentDto> publish(@PathVariable Long id,
                                       @RequestBody(required = false) NotePublishReq req) {
        return Result.ok(service.publish(id, req));
    }
}