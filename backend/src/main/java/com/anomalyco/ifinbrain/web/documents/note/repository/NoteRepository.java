package com.anomalyco.ifinbrain.web.documents.note.repository;

import com.anomalyco.ifinbrain.web.documents.note.entity.Note;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface NoteRepository extends JpaRepository<Note, Long>, JpaSpecificationExecutor<Note> {

    /** 统计角标：某人自己的活跃笔记数。 */
    long countByOwnerIdAndStatus(Long ownerId, Short status);

    /** 统计角标：某人作为共享对象的活跃笔记数。 */
    long countByScopeTypeAndStatus(String scopeType, Short status);
}