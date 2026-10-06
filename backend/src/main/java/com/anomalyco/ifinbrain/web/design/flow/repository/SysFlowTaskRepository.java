package com.anomalyco.ifinbrain.web.design.flow.repository;

import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowTask;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysFlowTaskRepository extends JpaRepository<SysFlowTask, Long> {

    /**
     * 当前用户的待办任务数 —— 对应 FlowEngineService.pendingTasks 的过滤条件
     *   status='PENDING' + assigneeType='USER' + assigneeId=:username
     *
     * dashboard 卡片用：避免拉全表过滤
     */
    long countByStatusAndAssigneeTypeAndAssigneeId(String status, String assigneeType, String assigneeId);
}