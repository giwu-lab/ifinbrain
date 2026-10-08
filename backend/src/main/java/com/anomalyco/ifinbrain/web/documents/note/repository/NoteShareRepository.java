package com.anomalyco.ifinbrain.web.documents.note.repository;

import com.anomalyco.ifinbrain.web.documents.note.entity.NoteShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoteShareRepository extends JpaRepository<NoteShare, Long> {

    List<NoteShare> findByNoteId(Long noteId);

    List<NoteShare> findByUserId(Long userId);

    Optional<NoteShare> findByNoteIdAndUserId(Long noteId, Long userId);

    /** 全量替换名单时先按笔记清空。 */
    void deleteByNoteId(Long noteId);

    /** 「共享给我的」列表需要按 user_id 反查笔记 id。 */
    @org.springframework.data.jpa.repository.Query("SELECT s.noteId FROM NoteShare s WHERE s.userId = :userId")
    List<Long> findNoteIdsByUserId(Long userId);
}