package com.anomalyco.ifinbrain.web.todo.repository;

import com.anomalyco.ifinbrain.web.todo.entity.TodoAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TodoAttachmentRepository extends JpaRepository<TodoAttachment, Long> {

    /** 任务下所有正常附件（按 created_at 倒序）。 */
    List<TodoAttachment> findByTaskIdAndStatusOrderByCreatedAtDesc(Long taskId, Short status);

    /** 计数：用于任务管理页列表 chip。 */
    long countByTaskIdAndStatus(Long taskId, Short status);

    Optional<TodoAttachment> findByIdAndStatus(Long id, Short status);

    @Modifying
    @Query("UPDATE TodoAttachment a SET a.status = 0 WHERE a.taskId = :taskId")
    int softDeleteByTaskId(@Param("taskId") Long taskId);
}