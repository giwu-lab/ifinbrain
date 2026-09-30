package com.anomalyco.ifinbrain.web.todo.repository;

import com.anomalyco.ifinbrain.web.todo.entity.TodoTaskTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TodoTaskTagRepository extends JpaRepository<TodoTaskTag, TodoTaskTag.PK> {

    /** 取某个任务的所有 tag id（用于列表 / 详情时回填 dto）。 */
    @Query("SELECT tt.tagId FROM TodoTaskTag tt WHERE tt.taskId = :taskId")
    List<Long> findTagIdsByTaskId(@Param("taskId") Long taskId);

    /** 一次性查一批 task 的 tag id（按 task_id 分组），用于列表渲染避免 N+1。 */
    @Query("SELECT tt.taskId, tt.tagId FROM TodoTaskTag tt WHERE tt.taskId IN :taskIds")
    List<Object[]> findTagIdsByTaskIds(@Param("taskIds") List<Long> taskIds);

    @Modifying
    @Query("DELETE FROM TodoTaskTag tt WHERE tt.taskId = :taskId")
    int deleteByTaskId(@Param("taskId") Long taskId);

    @Modifying
    @Query("DELETE FROM TodoTaskTag tt WHERE tt.tagId = :tagId")
    int deleteByTagId(@Param("tagId") Long tagId);

    /** 标签被引用的次数；>0 拒绝删除。 */
    long countByTagId(Long tagId);
}
