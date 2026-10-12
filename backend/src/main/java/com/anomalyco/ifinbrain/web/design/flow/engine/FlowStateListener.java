package com.anomalyco.ifinbrain.web.design.flow.engine;

/**
 * 流程状态变化回调 —— 业务模块据此回写自己的状态。
 *
 * <p><b>为什么不用现成的 {@link CallbackInvoker}</b>：它有四个问题让业务回写不可靠 ——
 * ① fire-and-forget（URL 分支 subscribe 后即返回），状态更新可能随进程结束丢失；
 * ② 捕获所有异常只记 warn，业务状态写失败无人知晓；
 * ③ URL/SPEL 两种通道都拿不到 {@code instanceId}，无法定位业务单据；
 * ④ 它的触发点至今没有接进状态机（全项目仅注入、从未调用）。
 * CallbackInvoker 保留作外部系统集成通道，不作业务回写主链路。
 *
 * <p><b>契约</b>：在推进流程的**同一事务内**同步调用。实现类自行 catch 并记录，
 * 不要向上抛 —— 审批动作本身已经发生，因为一条派生状态写失败而回滚审批，
 * 比状态写失败更糟（用户点了通过却看到「实例已结束」，且无从排查）。
 */
public interface FlowStateListener {

    /**
     * @param instanceId 实例 id
     * @param flowCode   流程 code（如 expense-sample）
     * @param businessKey 业务主键（发起方约定；报销场景 = 报销单号）
     * @param state      终态：APPROVED / REJECTED（CANCELED 未实现）
     * @param comment    审批意见；驳回时为驳回原因，通过时为通过备注（可能为 null）
     */
    void onFlowFinished(Long instanceId, String flowCode, String businessKey,
                        String state, String comment);

    /** 只关心这些流程的监听器返回 true —— 避免每个业务监听器都去解构事件体。 */
    default boolean supports(String flowCode) {
        return true;
    }
}