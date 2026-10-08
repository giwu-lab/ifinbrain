package com.anomalyco.ifinbrain.web.documents.note.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.documents.entity.ArchiveLibrary;
import com.anomalyco.ifinbrain.web.documents.note.entity.Note;
import com.anomalyco.ifinbrain.web.documents.note.entity.NoteShare;
import com.anomalyco.ifinbrain.web.documents.note.repository.NoteShareRepository;
import com.anomalyco.ifinbrain.web.documents.repository.ArchiveLibraryRepository;
import com.anomalyco.ifinbrain.web.entity.SysUser;
import com.anomalyco.ifinbrain.web.repository.SysUserRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 笔记可见性 / 可编辑性的<b>唯一判定源</b>。
 *
 * <p><b>为什么要单独一个类</b>：笔记的授权有三个维度（作者 / 范围 scope / 点名名单），
 * 判定逻辑一旦散在 Service 的各个方法里，必然出现「列表能看见但点进去 404」或者
 * 反过来「接口没拦但列表里漏了」的偏差。所有判定收在这里，且<b>读判定只有一个实现</b> ——
 * 单条访问（detail / update / delete 前置）也走 {@link Access#readable()} 的 Specification，
 * 而不是另写一份 Java if。少一份实现就少一处可能跑偏的地方。
 *
 * <p><b>为什么不用「先查再在 Java 里 filter」</b>：todo 模块踩过这个坑
 * （见 TodoTaskService.buildListSpec 注释）—— 那样会导致翻页漏数据、total 只统计当页。
 * 这里的列表查询是把可见性条件下推到 SQL，由数据库算 total。
 *
 * <p><b>note:manage-all 的语义</b>：只让管理员看到「自己部门相关」的笔记
 * （本部门 TEAM scope + owner 属于本部门的笔记），<b>不是</b>全库可见。
 * 共享类功能宁可 fail-closed；若日后确实需要全库只读，另开 {@code note:view-all}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteAclService {

    private final PermissionChecker perm;
    private final NoteShareRepository shareRepo;
    private final ArchiveLibraryRepository libraryRepo;
    private final SysUserRepository userRepo;

    /**
     * 构造一次请求级访问上下文。每个 Service 方法开头调一次（内含几个只读查询，
     * 但同一请求内复用同一实例，避免在 Specification 里反复查）。
     */
    public Access current() {
        Long me = perm.currentUserId();
        if (me == null) throw new BizException(401, "未登录");
        boolean manageAll = perm.has("note:manage-all");

        Long myDeptId = userRepo.findById(me).map(SysUser::getDeptId).orElse(null);

        List<NoteShare> mine = shareRepo.findByUserId(me);
        List<Long> sharedIds = new ArrayList<>(mine.size());
        Set<Long> writeIds = new HashSet<>();
        for (NoteShare s : mine) {
            sharedIds.add(s.getNoteId());
            if (NoteShare.GRANT_WRITE.equalsIgnoreCase(s.getGrantType())) writeIds.add(s.getNoteId());
        }

        // 档案库权限位要按 slug 拼（documents:{slug}:view / :update），只能逐库判，不能下推 SQL。
        // 档案库数量是个位数，一次全量扫描的代价可忽略，且只在本次请求内算一次。
        Set<Long> libraryView = new HashSet<>();
        Set<Long> libraryUpdate = new HashSet<>();
        for (ArchiveLibrary lib : libraryRepo.findAll()) {
            String slug = lib.getSlug();
            if (perm.has("documents:" + slug + ":view")) libraryView.add(lib.getId());
            if (perm.has("documents:" + slug + ":update")) libraryUpdate.add(lib.getId());
        }

        return new Access(me, manageAll, myDeptId, sharedIds, writeIds, libraryView, libraryUpdate,
                uid -> userRepo.findById(uid).map(SysUser::getDeptId).orElse(null));
    }

    /**
     * 一次请求内的访问上下文快照（不可变）。
     */
    public static final class Access {

        public static final String GRANT_OWNER = "OWNER";
        public static final String GRANT_WRITE = "WRITE";
        public static final String GRANT_READ  = "READ";

        private final Long userId;
        private final boolean manageAll;
        private final Long deptId;
        private final List<Long> sharedNoteIds;
        private final Set<Long> sharedWriteNoteIds;
        private final Set<Long> libraryViewIds;
        private final Set<Long> libraryUpdateIds;
        /** ownerId → 该用户所属部门。静态嵌套类不能直接摸外层字段，故由构造时注入。 */
        private final java.util.function.Function<Long, Long> deptOf;

        Access(Long userId, boolean manageAll, Long deptId,
               List<Long> sharedNoteIds, Set<Long> sharedWriteNoteIds,
               Set<Long> libraryViewIds, Set<Long> libraryUpdateIds,
               java.util.function.Function<Long, Long> deptOf) {
            this.userId = userId;
            this.manageAll = manageAll;
            this.deptId = deptId;
            this.sharedNoteIds = sharedNoteIds;
            this.sharedWriteNoteIds = sharedWriteNoteIds;
            this.libraryViewIds = libraryViewIds;
            this.libraryUpdateIds = libraryUpdateIds;
            this.deptOf = deptOf;
        }

        public Long userId() { return userId; }
        public boolean manageAll() { return manageAll; }
        public Long deptId() { return deptId; }

        /** 可读谓词。见类注释 —— 列表与单条访问共用这一个实现。 */
        public Specification<Note> readable() {
            return (root, q, cb) -> {
                List<Predicate> or = new ArrayList<>();
                or.add(cb.equal(root.get("ownerId"), userId));
                if (!sharedNoteIds.isEmpty()) or.add(root.get("id").in(sharedNoteIds));
                // 组织内共享：只要进了模块（有 note:view）就能读
                or.add(cb.equal(root.get("scopeType"), Note.SCOPE_ORG));
                if (deptId != null) {
                    or.add(cb.and(cb.equal(root.get("scopeType"), Note.SCOPE_TEAM),
                                  cb.equal(root.get("scopeDeptId"), deptId)));
                }
                if (!libraryViewIds.isEmpty()) {
                    or.add(cb.and(cb.equal(root.get("scopeType"), Note.SCOPE_LIBRARY),
                                  root.get("categoryId").in(libraryViewIds)));
                }
                if (manageAll) {
                    // 部门管理员：本部门 TEAM scope（已含上面）+ owner 属于本部门的任何笔记
                    or.add(root.<Long>get("ownerId").in(sameDeptUserIds(q, cb)));
                }
                return cb.or(or.toArray(new Predicate[0]));
            };
        }

        /** 可编辑谓词。范围比 readable 紧：ORG 默认只读。 */
        public Specification<Note> editable() {
            return (root, q, cb) -> {
                List<Predicate> or = new ArrayList<>();
                or.add(cb.equal(root.get("ownerId"), userId));
                if (!sharedWriteNoteIds.isEmpty()) or.add(root.get("id").in(sharedWriteNoteIds));
                or.add(cb.and(cb.equal(root.get("scopeType"), Note.SCOPE_TEAM),
                              cb.equal(root.get("scopeDeptId"), deptId)));
                if (!libraryUpdateIds.isEmpty()) {
                    or.add(cb.and(cb.equal(root.get("scopeType"), Note.SCOPE_LIBRARY),
                                  root.get("categoryId").in(libraryUpdateIds)));
                }
                if (manageAll) {
                    or.add(root.<Long>get("ownerId").in(sameDeptUserIds(q, cb)));
                }
                return cb.or(or.toArray(new Predicate[0]));
            };
        }

        private Subquery<Long> sameDeptUserIds(jakarta.persistence.criteria.CriteriaQuery<?> q,
                                               jakarta.persistence.criteria.CriteriaBuilder cb) {
            Subquery<Long> sq = q.subquery(Long.class);
            Root<SysUser> u = sq.from(SysUser.class);
            sq.select(u.get("id")).where(cb.equal(u.get("deptId"), deptId));
            return sq;
        }

        /** 组合：某条笔记是否可读 / 可编。列表查询里用来打权限位。 */
        public boolean canRead(Note note) {
            if (note == null) return false;
            if (userId.equals(note.getOwnerId())) return true;
            if (sharedNoteIds.contains(note.getId())) return true;
            String scope = note.getScopeType();
            if (Note.SCOPE_ORG.equals(scope)) return true;
            if (Note.SCOPE_TEAM.equals(scope) && deptId != null && deptId.equals(note.getScopeDeptId())) return true;
            if (Note.SCOPE_LIBRARY.equals(scope) && libraryViewIds.contains(note.getCategoryId())) return true;
            return manageAll && ownerInMyDept(note.getOwnerId());
        }

        public boolean canEdit(Note note) {
            if (note == null) return false;
            if (userId.equals(note.getOwnerId())) return true;
            if (sharedWriteNoteIds.contains(note.getId())) return true;
            String scope = note.getScopeType();
            if (Note.SCOPE_TEAM.equals(scope) && deptId != null && deptId.equals(note.getScopeDeptId())) return true;
            if (Note.SCOPE_LIBRARY.equals(scope) && libraryUpdateIds.contains(note.getCategoryId())) return true;
            return manageAll && ownerInMyDept(note.getOwnerId());
        }

        /** 能否改可见性 / 共享名单：作者本人，或能管到作者的部门管理员。 */
        public boolean canShare(Note note) {
            if (note == null) return false;
            if (userId.equals(note.getOwnerId())) return true;
            return manageAll && ownerInMyDept(note.getOwnerId());
        }

        public boolean isOwner(Note note) {
            return note != null && userId.equals(note.getOwnerId());
        }

        public boolean canDelete(Note note, boolean deleteAny) {
            if (note == null) return false;
            return deleteAny || userId.equals(note.getOwnerId());
        }

        /** 本篇上的授权等级，用于 DTO.myGrant 与「共享给我」标记。 */
        public String grantOf(Note note) {
            if (note == null) return null;
            if (userId.equals(note.getOwnerId())) return GRANT_OWNER;
            if (sharedWriteNoteIds.contains(note.getId())) return GRANT_WRITE;
            if (sharedNoteIds.contains(note.getId())) return GRANT_READ;
            return null;
        }

        public boolean sharedWithMe(Note note) {
            if (note == null) return false;
            String g = grantOf(note);
            return GRANT_READ.equals(g) || GRANT_WRITE.equals(g);
        }

        /** 该档案库我是否有 update 权限（发布为 .md 时用）。 */
        public boolean canUpdateLibrary(Long categoryId) {
            return categoryId != null && libraryUpdateIds.contains(categoryId);
        }

        /** 该档案库我是否有 view 权限（挑「挂哪个库」时用）。 */
        public boolean canReadLibrary(Long categoryId) {
            return categoryId != null && libraryViewIds.contains(categoryId);
        }

        private boolean ownerInMyDept(Long ownerId) {
            if (!manageAll || deptId == null || ownerId == null) return false;
            return deptId.equals(deptOf.apply(ownerId));
        }

        /** 组合谓词工具：id = ? */
        public static Specification<Note> idEq(Long id) {
            return (root, q, cb) -> cb.equal(root.get("id"), id);
        }
    }
}