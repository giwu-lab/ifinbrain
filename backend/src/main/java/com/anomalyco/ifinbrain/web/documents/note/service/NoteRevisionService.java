package com.anomalyco.ifinbrain.web.documents.note.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.documents.note.dto.NoteRevisionDto;
import com.anomalyco.ifinbrain.web.documents.note.entity.Note;
import com.anomalyco.ifinbrain.web.documents.note.entity.NoteRevision;
import com.anomalyco.ifinbrain.web.documents.note.repository.NoteRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 版本快照。每次保存写一条<b>保存前</b>的内容；保留最近 50 条/篇。
 *
 * <p>为什么快照而不是只留最后一次：团队笔记里最贵的错误是「A 写了两小时，B 一键保存覆盖掉」。
 * 有了历史，回滚就是一次普通保存（历史不丢），冲突时也能看到上一版。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteRevisionService {

    /** 每篇最多保留的快照数。长笔记无限膨胀会拖垮详情页。 */
    private static final int KEEP = 50;

    private final NoteRevisionRepository repo;

    /**
     * 保存前快照。{@code version == 0} 表示这篇从没被保存过（新建后第一次改），
     * 此时没有「上一版」可留，直接跳过。
     */
    @Transactional
    public void snapshot(Note note, Long editorId, String editorName) {
        Integer v = note.getVersion();
        if (v == null || v <= 0) return;
        if (repo.findByNoteIdAndRevision(note.getId(), v).isPresent()) return;

        repo.save(NoteRevision.builder()
                .noteId(note.getId())
                .revision(v)
                .title(note.getTitle())
                .content(note.getContent() == null ? "" : note.getContent())
                .editorId(editorId)
                .editorName(editorName)
                .build());

        prune(note.getId());
    }

    /** 只保留最近 KEEP 条。 */
    private void prune(Long noteId) {
        List<NoteRevision> all = repo.findByNoteIdOrderByRevisionDesc(noteId);
        if (all.size() <= KEEP) return;
        Integer cutoff = all.get(KEEP).getRevision();   // 第 KEEP+1 新的一条，从它起全删
        repo.deleteByNoteIdAndRevisionLessThanEqual(noteId, cutoff);
        log.info("[note] pruned revisions of note={} cutoff<={}", noteId, cutoff);
    }

    /** 版本列表（不含正文 —— 列表页只显示「第 N 版 · 谁 · 什么时候」）。 */
    @Transactional(readOnly = true)
    public List<NoteRevisionDto> list(Long noteId) {
        return repo.findByNoteIdOrderByRevisionDesc(noteId).stream()
                .map(r -> NoteRevisionDto.builder()
                        .revision(r.getRevision())
                        .title(r.getTitle())
                        .editorName(r.getEditorName())
                        .createdAt(r.getCreatedAt())
                        .build())
                .toList();
    }

    /** 单个版本详情（含正文，用于查看 / 回滚）。 */
    @Transactional(readOnly = true)
    public NoteRevisionDto detail(Long noteId, int revision) {
        NoteRevision r = repo.findByNoteIdAndRevision(noteId, revision)
                .orElseThrow(() -> new BizException(404, "该版本不存在"));
        return NoteRevisionDto.builder()
                .revision(r.getRevision())
                .title(r.getTitle())
                .content(r.getContent())
                .editorName(r.getEditorName())
                .createdAt(r.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public boolean exists(Long noteId, int revision) {
        return repo.findByNoteIdAndRevision(noteId, revision).isPresent();
    }
}