package com.anomalyco.ifinbrain.web.todo.repository;

import com.anomalyco.ifinbrain.web.todo.entity.TodoTaskTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TodoTaskTagRepository extends JpaRepository<TodoTaskTag, TodoTaskTag.PK> {

    /** 取某个任务的所有 dict_item_id（用于列表 / 详情时回填 dto）。 */
    @Query("SELECT tt.dictItemId FROM TodoTaskTag tt WHERE tt.taskId = :taskId")
    List<Long> findDictItemIdsByTaskId(@Param("taskId") Long taskId);

    /** 一次性查一批 task 的 dict_item_id（按 task_id 分组），用于列表渲染避免 N+1。 */
    @Query("SELECT tt.taskId, tt.dictItemId FROM TodoTaskTag tt WHERE tt.taskId IN :taskIds")
    List<Object[]> findDictItemIdsByTaskIds(@Param("taskIds") List<Long> taskIds);

    /** 全库被引用的 dict_item_id 集合 —— 孤儿标签清理用。 */
    @Query("SELECT DISTINCT tt.dictItemId FROM TodoTaskTag tt")
    List<Long> findAllReferencedDictItemIds();

    @Modifying
    @Query("DELETE FROM TodoTaskTag tt WHERE tt.taskId = :taskId")
    int deleteByTaskId(@Param("taskId") Long taskId);

    @Modifying
    @Query("DELETE FROM TodoTaskTag tt WHERE tt.dictItemId = :dictItemId")
    int deleteByDictItemId(@Param("dictItemId") Long dictItemId);

    /** 标签被引用的次数；>0 拒绝删除。 */
    long countByDictItemId(Long dictItemId);
}
