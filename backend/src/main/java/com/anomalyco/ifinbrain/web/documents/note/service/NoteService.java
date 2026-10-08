package com.anomalyco.ifinbrain.web.documents.note.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.documents.dto.ArchiveLibraryDto;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentDto;
import com.anomalyco.ifinbrain.web.documents.entity.ArchiveLibrary;
import com.anomalyco.ifinbrain.web.documents.note.dto.*;
import com.anomalyco.ifinbrain.web.documents.note.entity.Note;
import com.anomalyco.ifinbrain.web.documents.note.entity.NoteShare;
import com.anomalyco.ifinbrain.web.documents.note.repository.NoteRepository;
import com.anomalyco.ifinbrain.web.documents.note.repository.NoteShareRepository;
import com.anomalyco.ifinbrain.web.documents.repository.ArchiveLibraryRepository;
import com.anomalyco.ifinbrain.web.documents.service.DocumentService;
import com.anomalyco.ifinbrain.web.entity.SysDept;
import com.anomalyco.ifinbrain.web.entity.SysUser;
import com.anomalyco.ifinbrain.web.repository.SysDeptRepository;
import com.anomalyco.ifinbrain.web.repository.SysUserRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 笔记服务。
 *
 * <p>每个公开方法的第一件事都是取 {@link NoteAclService.Access}，读操作走
 * {@code access.readable()}、写操作走 {@code access.editable()}，让 ACL 判定只有一处实现
 * （见 NoteAclService 类注释）。
 *
 * <p>统一的越权响应：<b>404 而不是 403</b>。笔记是共享类资源，403 等于告诉调用者
 * 「这篇笔记存在，你只是没权限」，那是存在性泄露。找不到和看不到，长得一模一样。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteService {

    /** 正文上限 1MB。TEXT 能存 1GB，但列表页 / 自动保存都不该被单篇巨型文档拖死。 */
    private static final int MAX_CONTENT_BYTES = 1024 * 1024;
    private static final int MAX_TAGS = 10;
    private static final int MAX_TAG_LEN = 24;
    /** 列表摘要长度（纯文本） */
    private static final int EXCERPT_LEN = 200;
    private static final int MAX_PAGE_SIZE = 200;

    private final NoteRepository noteRepo;
    private final NoteShareRepository shareRepo;
    private final NoteRevisionService revisionService;
    private final NoteAclService acl;
    private final PermissionChecker perm;
    private final ArchiveLibraryRepository libraryRepo;
    private final SysUserRepository userRepo;
    private final SysDeptRepository deptRepo;
    private final DocumentService documentService;

    // ===================== 查询 =====================

    /**
     * 列表。scope 参数把「我的 / 共享给我 / 团队 / 档案库 / 已归档」几种视角收敛到一个入口。
     *
     * <p>可见性谓词由 {@link NoteAclService.Access#readable()} 提供并下推到 SQL ——
     * 绝不先查一页再在 Java 里 filter（todo 模块踩过，翻页会漏数据、total 会失真）。
     */
    @Transactional(readOnly = true)
    public Page<NoteDto> page(int page, int size, String scope, String keyword, String tag,
                              Long categoryId, Boolean onlyArchived, Boolean onlyPinned) {
        perm.require("note:view");
        NoteAclService.Access access = acl.current();

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), safeSize,
                Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));

        Specification<Note> spec = access.readable();
        String s = norm(scope);

        if ("mine".equals(s)) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("ownerId"), access.userId()));
        } else if ("shared".equals(s)) {
            spec = spec.and((r, q, cb) -> cb.and(
                    cb.notEqual(r.get("ownerId"), access.userId()),
                    r.get("id").in(allSharedIds(access))));
        } else if ("team".equals(s)) {
            if (access.deptId() == null) {
                return Page.empty(pageable);
            }
            spec = spec.and((r, q, cb) -> cb.and(
                    cb.equal(r.get("scopeType"), Note.SCOPE_TEAM),
                    cb.equal(r.get("scopeDeptId"), access.deptId())));
        } else if ("library".equals(s)) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("scopeType"), Note.SCOPE_LIBRARY));
        }

        // 归档态：只有「归档」tab 或显式 onlyArchived 才看得到，否则一律活跃。
        // （软删除未启用，status 是唯一的"不显示"开关，别让两个条件互相打架。）
        boolean archived = "archived".equals(s) || Boolean.TRUE.equals(onlyArchived);
        spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), archived ? (short) 0 : (short) 1));

        if (Boolean.TRUE.equals(onlyPinned)) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("pinned"), (short) 1));
        }
        if (categoryId != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("categoryId"), categoryId));
        }
        if (tag != null && !tag.isBlank()) {
            // tags 规范化成 ",a,b,"，包住两端才能做精确 LIKE（否则 "财" 会命中 "财务"）
            String needle = "%," + tag.trim().toLowerCase() + ",%";
            spec = spec.and((r, q, cb) -> cb.like(cb.lower(r.get("tags")), needle));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + keyword.trim().toLowerCase() + "%";
            spec = spec.and((r, q, cb) -> cb.or(
                    cb.like(cb.lower(r.get("title")), kw),
                    cb.like(cb.lower(cb.coalesce(r.get("content"), "")), kw)));
        }

        Page<Note> result = noteRepo.findAll(spec, pageable);
        return new PageImpl<>(
                toListDtos(result.getContent(), access), pageable, result.getTotalElements());
    }

    /** tab 角标。 */
    @Transactional(readOnly = true)
    public NoteStatsDto stats() {
        perm.require("note:view");
        NoteAclService.Access access = acl.current();
        Long me = access.userId();

        List<Long> sharedIds = allSharedIds(access);

        long mine = noteRepo.count(active().and((r, q, cb) -> cb.equal(r.get("ownerId"), me)));

        long shared = 0;
        if (!sharedIds.isEmpty()) {
            shared = noteRepo.count(active().and((r, q, cb) -> cb.and(
                    cb.notEqual(r.get("ownerId"), me), r.get("id").in(sharedIds))));
        }
        long team = 0;
        if (access.deptId() != null) {
            team = noteRepo.count(active().and((r, q, cb) -> cb.and(
                    cb.equal(r.get("scopeType"), Note.SCOPE_TEAM),
                    cb.equal(r.get("scopeDeptId"), access.deptId()))));
        }
        long archived = noteRepo.count(((Specification<Note>) (r, q, cb) -> cb.equal(r.get("status"), (short) 0))
                .and((r, q, cb) -> cb.equal(r.get("ownerId"), me)));

        return NoteStatsDto.builder().mine(mine).shared(shared).team(team).archived(archived).build();
    }

    @Transactional(readOnly = true)
    public NoteDto detail(Long id) {
        perm.require("note:view");
        NoteAclService.Access access = acl.current();
        Note note = findReadable(id, access);
        NoteDto dto = toDto(note, access);
        dto.setContent(note.getContent());          // 详情才带正文
        dto.setTags(parseTags(note.getTags()));
        return dto;
    }

    // ===================== 写入 =====================

    @Transactional
    public NoteDto create(NoteCreateReq req) {
        perm.require("note:create");
        NoteAclService.Access access = acl.current();

        String scope = normalizeScope(req.getScopeType());
        validateScopePayload(scope, req.getScopeDeptId(), req.getCategoryId());
        // 挂档案库时先确认目标库确实可写 —— 否则等于给无权编辑的库建篇笔记却没人能改
        if (Note.SCOPE_LIBRARY.equals(scope) && !access.canUpdateLibrary(req.getCategoryId())) {
            throw new BizException(403, "无权在该档案库创建笔记");
        }

        Note note = Note.builder()
                .title(req.getTitle().trim())
                .content(limitContent(req.getContent()))
                .scopeType(scope)
                .scopeDeptId(Note.SCOPE_TEAM.equals(scope) ? req.getScopeDeptId() : null)
                .categoryId(Note.SCOPE_LIBRARY.equals(scope) ? req.getCategoryId() : null)
                .tags(normalizeTags(req.getTags()))
                .pinned((short) 0)
                .status((short) 1)
                .relatedDocumentId(req.getRelatedDocumentId())
                .ownerId(access.userId())
                .ownerName(perm.currentUsername())
                .lastEditorId(access.userId())
                .lastEditorName(perm.currentUsername())
                .version(0)
                .build();
        note = noteRepo.save(note);

        log.info("[note] created id={} scope={} owner={}", note.getId(), scope, access.userId());
        return toDto(note, access);
    }

    @Transactional
    public NoteDto update(Long id, NoteUpdateReq req) {
        perm.require("note:update");
        NoteAclService.Access access = acl.current();
        Note note = findEditable(id, access);

        // 乐观锁：客户端带上打开时读到的 version，不一致说明期间有人改过 → 409 交还前端决策
        if (req.getVersion() == null || !req.getVersion().equals(note.getVersion())) {
            throw new BizException(409, "笔记已被 " +
                    (note.getLastEditorName() == null ? "他人" : note.getLastEditorName()) +
                    " 修改，请先查看最新内容（当前版本 v" + note.getVersion() + "）");
        }

        String scope = normalizeScope(req.getScopeType());
        // 只有能改共享的人才能改可见性；普通协作者（TEAM / 名单 WRITE）只能改正文和标签
        if (!scope.equals(note.getScopeType())
                || !Objects.equals(req.getScopeDeptId(), note.getScopeDeptId())
                || !Objects.equals(req.getCategoryId(), note.getCategoryId())) {
            if (!access.canShare(note)) {
                throw new BizException(403, "无权修改这篇笔记的可见范围");
            }
            validateScopePayload(scope, req.getScopeDeptId(), req.getCategoryId());
            if (Note.SCOPE_LIBRARY.equals(scope) && !access.canUpdateLibrary(req.getCategoryId())) {
                throw new BizException(403, "无权把笔记挂到该档案库");
            }
            note.setScopeDeptId(Note.SCOPE_TEAM.equals(scope) ? req.getScopeDeptId() : null);
            note.setCategoryId(Note.SCOPE_LIBRARY.equals(scope) ? req.getCategoryId() : null);
        }

        // 保存前快照（团队笔记的兜底：互相覆盖后还能回来）
        revisionService.snapshot(note, access.userId(), perm.currentUsername());

        note.setTitle(req.getTitle().trim());
        note.setContent(limitContent(req.getContent()));
        note.setScopeType(scope);
        note.setTags(normalizeTags(req.getTags()));
        note.setRelatedDocumentId(req.getRelatedDocumentId());
        note.setLastEditorId(access.userId());
        note.setLastEditorName(perm.currentUsername());
        note.setVersion(note.getVersion() + 1);
        note = noteRepo.save(note);

        return toDto(note, access);
    }

    @Transactional
    public void delete(Long id) {
        perm.require("note:delete");
        NoteAclService.Access access = acl.current();
        Note note = noteRepo.findById(id).orElse(null);
        if (note == null) throw new BizException(404, "笔记不存在或无权访问");
        // 删除比编辑更窄：作者本人，或有 note:delete-any（admin）
        if (!access.canDelete(note, perm.has("note:delete-any"))) {
            throw new BizException(403, "只能删除自己创建的笔记");
        }
        // share / revision 由 DB 外键 ON DELETE CASCADE 连带清掉
        noteRepo.delete(note);
        log.info("[note] deleted id={} owner={}", id, note.getOwnerId());
    }

    @Transactional
    public NoteDto setPinned(Long id, boolean pinned) {
        perm.require("note:update");
        NoteAclService.Access access = acl.current();
        Note note = findEditable(id, access);
        note.setPinned((short) (pinned ? 1 : 0));
        return toDto(noteRepo.save(note), access);
    }

    /** 归档 / 恢复。归档是业务状态不是删除（软删除未启用，行还在）。 */
    @Transactional
    public NoteDto setArchived(Long id, boolean archived) {
        perm.require("note:update");
        NoteAclService.Access access = acl.current();
        Note note = findEditable(id, access);
        note.setStatus((short) (archived ? 0 : 1));
        return toDto(noteRepo.save(note), access);
    }

    // ===================== 共享名单 =====================

    @Transactional(readOnly = true)
    public List<NoteShareDto> listShares(Long id) {
        perm.require("note:view");
        NoteAclService.Access access = acl.current();
        Note note = findReadable(id, access);

        List<NoteShare> shares = shareRepo.findByNoteId(note.getId());
        Map<Long, SysUser> users = userRepo.findAllById(
                        shares.stream().map(NoteShare::getUserId).toList())
                .stream().collect(Collectors.toMap(SysUser::getId, Function.identity()));
        Map<Long, String> depts = deptNameMap();

        return shares.stream().map(s -> {
            SysUser u = users.get(s.getUserId());
            return NoteShareDto.builder()
                    .userId(s.getUserId())
                    .username(u == null ? null : u.getUsername())
                    .displayName(u == null ? ("已删除用户 #" + s.getUserId()) : u.getDisplayName())
                    .deptId(u == null ? null : u.getDeptId())
                    .deptName(u == null ? null : depts.get(u.getDeptId()))
                    .grantType(s.getGrantType())
                    .createdAt(s.getCreatedAt())
                    .build();
        }).toList();
    }

    /**
     * 全量替换共享名单（对齐 ArchiveLibraryController.updatePerms 的体例）。
     * 不做增删改三件套：对话框一次提交整个名单，前端就不用维护差集状态。
     */
    @Transactional
    public List<NoteShareDto> replaceShares(Long id, NoteShareUpdateReq req) {
        perm.require("note:share");
        NoteAclService.Access access = acl.current();
        Note note = noteRepo.findById(id).orElse(null);
        if (note == null || !access.canRead(note)) throw new BizException(404, "笔记不存在或无权访问");
        if (!access.canShare(note)) throw new BizException(403, "无权修改这篇笔记的共享名单");

        List<NoteShareUpdateReq.Item> items = req.getItems() == null ? List.of() : req.getItems();
        Set<Long> validUsers = new LinkedHashSet<>();
        for (NoteShareUpdateReq.Item it : items) {
            if (it.getUserId() != null) validUsers.add(it.getUserId());
        }
        // 只接受启用状态的真实用户：给一个不存在/已停用的 id 授权 = 授权给空气
        Set<Long> enabled = userRepo.findAllByStatusOrderByUsernameAsc((short) 1).stream()
                .map(SysUser::getId).collect(Collectors.toSet());
        validUsers.retainAll(enabled);

        shareRepo.deleteByNoteId(note.getId());
        shareRepo.flush();
        for (NoteShareUpdateReq.Item it : items) {
            Long uid = it.getUserId();
            if (uid == null || !enabled.contains(uid)) continue;
            String grant = NoteShare.GRANT_WRITE.equalsIgnoreCase(it.getGrantType())
                    ? NoteShare.GRANT_WRITE : NoteShare.GRANT_READ;
            if (access.userId().equals(uid)) continue;   // 作者本人不占名单行
            shareRepo.save(NoteShare.builder()
                    .noteId(note.getId()).userId(uid).grantType(grant)
                    .createdBy(access.userId()).build());
        }
        return listShares(id);
    }

    // ===================== 版本历史 =====================

    @Transactional(readOnly = true)
    public List<NoteRevisionDto> listRevisions(Long id) {
        perm.require("note:view");
        NoteAclService.Access access = acl.current();
        return revisionService.list(findReadable(id, access).getId());
    }

    @Transactional(readOnly = true)
    public NoteRevisionDto revision(Long id, int revision) {
        perm.require("note:view");
        NoteAclService.Access access = acl.current();
        return revisionService.detail(findReadable(id, access).getId(), revision);
    }

    /** 回滚 = 把旧版本内容当作一次普通保存提交（历史不丢，会多出一条快照）。 */
    @Transactional
    public NoteDto restore(Long id, int revision) {
        perm.require("note:update");
        NoteAclService.Access access = acl.current();
        Note note = findEditable(id, access);
        NoteRevisionDto old = revisionService.detail(note.getId(), revision);

        revisionService.snapshot(note, access.userId(), perm.currentUsername());
        note.setTitle(old.getTitle());
        note.setContent(limitContent(old.getContent()));
        note.setLastEditorId(access.userId());
        note.setLastEditorName(perm.currentUsername());
        note.setVersion(note.getVersion() + 1);
        note = noteRepo.save(note);

        log.info("[note] restored id={} from v{} → v{}", id, revision, note.getVersion());
        return toDto(note, access);
    }

    // ===================== 发布到档案库 =====================

    /**
     * 把笔记发布为档案库里的 .md 文件。
     *
     * <p>单向一次性动作，不是双写：笔记仍是唯一真相源，档案里那份是归档件。
     * 权限要求两级：能编辑这篇笔记 + 对目标档案库有 upload。
     */
    @Transactional
    public DocumentDto publish(Long id, NotePublishReq req) {
        NoteAclService.Access access = acl.current();
        Note note = findEditable(id, access);

        Long categoryId = (req != null && req.getCategoryId() != null)
                ? req.getCategoryId() : note.getCategoryId();
        if (categoryId == null) throw new BizException(400, "请选择要发布到的档案库");
        if (!access.canUpdateLibrary(categoryId)) {
            throw new BizException(403, "无权在该档案库发布文件");
        }
        String folder = (req != null) ? req.getFolderPath() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(note.getTitle()).append("\n\n");
        List<String> tags = parseTags(note.getTags());
        if (!tags.isEmpty()) sb.append("> ").append(String.join(" · ", tags)).append("\n\n");
        sb.append(note.getContent() == null ? "" : note.getContent());

        String filename = note.getTitle().trim();
        DocumentDto dto = documentService.publishText(categoryId, folder, filename + ".md", sb.toString());
        log.info("[note] published note={} → document={} cat={}", id, dto.getId(), categoryId);
        return dto;
    }

    // ===================== 候选项 =====================

    /** 共享对话框的候选用户（只启用的，按 username 升序）。 */
    @Transactional(readOnly = true)
    public List<NoteCandidateDto> candidates() {
        perm.require("note:share");
        Map<Long, String> depts = deptNameMap();
        return userRepo.findAllByStatusOrderByUsernameAsc((short) 1).stream()
                .map(u -> NoteCandidateDto.builder()
                        .id(u.getId())
                        .username(u.getUsername())
                        .displayName(u.getDisplayName())
                        .deptId(u.getDeptId())
                        .deptName(depts.get(u.getDeptId()))
                        .build())
                .toList();
    }

    /** 可关联的档案库：只返当前用户有 view 的那些。 */
    @Transactional(readOnly = true)
    public List<ArchiveLibraryDto> libraries() {
        perm.require("note:view");
        NoteAclService.Access access = acl.current();
        List<ArchiveLibrary> all = libraryRepo.findAll();
        return all.stream()
                .filter(lib -> access.canReadLibrary(lib.getId()))
                .sorted((a, b) -> Integer.compare(
                        a.getSort() == null ? 0 : a.getSort(),
                        b.getSort() == null ? 0 : b.getSort()))
                .map(ArchiveLibraryDto::fromEntity)
                .toList();
    }

    // ===================== 内部 =====================

    /** 可读地取一条；取不到 = 不存在或不可见，一律 404（不泄露存在性）。 */
    private Note findReadable(Long id, NoteAclService.Access access) {
        return noteRepo.findOne(access.readable().and(NoteAclService.Access.idEq(id)))
                .orElseThrow(() -> new BizException(404, "笔记不存在或无权访问"));
    }

    private Note findEditable(Long id, NoteAclService.Access access) {
        return noteRepo.findOne(access.editable().and(NoteAclService.Access.idEq(id)))
                .orElseThrow(() -> new BizException(404, "笔记不存在或无权编辑"));
    }

    private List<Long> allSharedIds(NoteAclService.Access access) {
        return shareRepo.findNoteIdsByUserId(access.userId());
    }

    private static Specification<Note> active() {
        return (r, q, cb) -> cb.equal(r.get("status"), (short) 1);
    }

    // ---- DTO 组装（含批量富化，避免每行一次查询） ----

    private List<NoteDto> toListDtos(List<Note> notes, NoteAclService.Access access) {
        if (notes.isEmpty()) return List.of();
        Rich r = enrich(notes);
        List<NoteDto> out = new ArrayList<>(notes.size());
        for (Note n : notes) {
            NoteDto dto = toDto(n, access, r);
            dto.setTags(parseTags(n.getTags()));
            out.add(dto);
        }
        return out;
    }

    private NoteDto toDto(Note note, NoteAclService.Access access) {
        NoteDto dto = toDto(note, access, enrich(List.of(note)));
        dto.setTags(parseTags(note.getTags()));
        return dto;
    }

    private NoteDto toDto(Note note, NoteAclService.Access access, Rich r) {
        boolean owner = access.isOwner(note);
        return NoteDto.builder()
                .id(note.getId())
                .title(note.getTitle())
                .excerpt(excerpt(note.getContent()))
                .scopeType(note.getScopeType())
                .scopeDeptId(note.getScopeDeptId())
                .scopeDeptName(nameOf(r.deptNames, note.getScopeDeptId()))
                .categoryId(note.getCategoryId())
                .categoryName(nameOf(r.libNames, note.getCategoryId()))
                .pinned(note.getPinned() != null && note.getPinned() == 1)
                .status(note.getStatus())
                .relatedDocumentId(note.getRelatedDocumentId())
                .relatedDocumentName(nameOf(r.docNames, note.getRelatedDocumentId()))
                .ownerId(note.getOwnerId())
                .ownerName(note.getOwnerName())
                .lastEditorName(note.getLastEditorName())
                .version(note.getVersion())
                .createdAt(note.getCreatedAt())
                .updatedAt(note.getUpdatedAt())
                .canEdit(access.canEdit(note))
                .canShare(access.canShare(note))
                .sharedWithMe(access.sharedWithMe(note))
                .myGrant(access.grantOf(note))
                .owner(owner)
                .build();
    }

    /** 批量富化：部门名 / 档案库名 / 关联档案名，各一次查询。 */
    private record Rich(Map<Long, String> deptNames, Map<Long, String> libNames,
                        Map<Long, String> docNames) {}

    /**
     * 富化查表的 null 安全取值。
     *
     * <p>踩过的坑：这四个外键（scopeDeptId / categoryId / relatedDocumentId）都可能是 null，
     * 而「本批次全是 null」时 enrich() 会返回 {@code Map.of()} —— JDK 的不可变 Map
     * 对 {@code get(null)} 直接抛 NPE（不是返回 null）。于是 PERSONAL 笔记一建就 500。
     * 普通 HashMap 恰好容忍 null key，所以只有空 Map 这条路径炸，很容易漏测。
     */
    private static String nameOf(Map<Long, String> names, Long id) {
        return id == null ? null : names.get(id);
    }

    private Rich enrich(List<Note> notes) {
        Set<Long> deptIds = notes.stream().map(Note::getScopeDeptId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> depts = deptIds.isEmpty() ? Map.of()
                : deptRepo.findAllById(deptIds).stream()
                    .collect(Collectors.toMap(SysDept::getId, SysDept::getName));

        Set<Long> catIds = notes.stream().map(Note::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> libs = catIds.isEmpty() ? Map.of()
                : libraryRepo.findAllById(catIds).stream()
                    .collect(Collectors.toMap(ArchiveLibrary::getId, ArchiveLibrary::getName));

        Set<Long> docIds = notes.stream().map(Note::getRelatedDocumentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> docs = docIds.isEmpty() ? Map.of()
                : documentService.findNames(docIds);

        return new Rich(depts, libs, docs);
    }

    private Map<Long, String> deptNameMap() {
        return deptRepo.findAll().stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getName));
    }

    // ---- 校验 / 规范化 ----

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String normalizeScope(String s) {
        String v = s == null || s.isBlank() ? Note.SCOPE_PERSONAL : s.trim().toUpperCase();
        return switch (v) {
            case Note.SCOPE_PERSONAL, Note.SCOPE_TEAM, Note.SCOPE_LIBRARY, Note.SCOPE_ORG -> v;
            default -> throw new BizException(400, "scopeType 只能是 PERSONAL / TEAM / LIBRARY / ORG");
        };
    }

    private static void validateScopePayload(String scope, Long deptId, Long categoryId) {
        if (Note.SCOPE_TEAM.equals(scope) && deptId == null) {
            throw new BizException(400, "团队笔记必须指定部门");
        }
        if (Note.SCOPE_LIBRARY.equals(scope) && categoryId == null) {
            throw new BizException(400, "档案库笔记必须指定档案库");
        }
    }

    private static String limitContent(String content) {
        String c = content == null ? "" : content;
        if (c.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new BizException(413, "笔记正文超过 1MB 上限");
        }
        return c;
    }

    /** 标签规范化：去空白、去重、限长，最后包成 ",a,b," 以便精确 LIKE。 */
    private static String normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String raw : tags) {
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.length() > MAX_TAG_LEN) {
                throw new BizException(400, "单个标签不能超过 " + MAX_TAG_LEN + " 个字符");
            }
            set.add(t);
        }
        if (set.size() > MAX_TAGS) {
            throw new BizException(400, "标签不能超过 " + MAX_TAGS + " 个");
        }
        if (set.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : set) {
            sb.append(',').append(t.toLowerCase());
        }
        return sb.append(',').toString();
    }

    private static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** markdown → 纯文本摘要。够用即可，不追求 CommonMark 全量还原。 */
    static String excerpt(String md) {
        if (md == null || md.isBlank()) return "";
        String s = md;
        s = s.replaceAll("(?s)```.*?```", " ");          // 代码块
        s = s.replaceAll("`[^`]*`", " ");                  // 行内代码
        s = s.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ");   // 图片
        s = s.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1"); // 链接留文字
        s = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");     // 标题井号
        s = s.replaceAll("(?m)^\\s{0,3}>\\s?", "");          // 引用
        s = s.replaceAll("(?m)^\\s{0,3}([-*+])\\s+", "");    // 无序列表
        s = s.replaceAll("(?m)^\\s{0,3}\\d+\\.\\s+", "");   // 有序列表
        s = s.replaceAll("(?m)^\\s{0,3}([-*_])\\s*(\\1\\s*){2,}$", " ");  // 分隔线
        s = s.replace("*", "").replace("_", "").replace("~", "");
        s = s.replaceAll("\\|", " ");                        // 表格竖线
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("\\s*\\n\\s*", " ");
        s = s.trim();
        return s.length() <= EXCERPT_LEN ? s : s.substring(0, EXCERPT_LEN) + "…";
    }
}