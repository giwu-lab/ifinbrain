package com.anomalyco.ifinbrain.web.documents.note.repository;

import com.anomalyco.ifinbrain.web.documents.note.entity.NoteRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoteRevisionRepository extends JpaRepository<NoteRevision, Long> {

    /** 倒序取最近 N 条（= revision desc）。 */
    List<NoteRevision> findByNoteIdOrderByRevisionDesc(Long noteId);

    Optional<NoteRevision> findByNoteIdAndRevision(Long noteId, Integer revision);

    long countByNoteId(Long noteId);

    long countByNoteIdAndRevisionGreaterThan(Long noteId, Integer revision);

    /** 裁剪：只保留 revision > keepAfter 的记录。 */
    void deleteByNoteIdAndRevisionLessThanEqual(Long noteId, Integer revision);
}