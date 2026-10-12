package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.design.flow.engine.FlowStateListener;
import com.anomalyco.ifinbrain.web.repository.InvoiceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报销流程终态 → 发票报销状态回写。
 *
 * <p>约定：{@code businessKey} = **报销单号**（DB 列 {@code verification_no}）。
 * 用报销单号而不是发票号，是因为一次提交可能包含多张发票、共享同一个报销单号 ——
 * 一张报销单 = 一个流程实例，审批通过时该单下所有发票一起转「已报销」。
 *
 * <p>用发票号当 businessKey 会撞上引擎的 (flowId, businessKey) 唯一约束语义，
 * 也会让一次多发票提交产生 N 个审批实例、审批人点 N 次「通过」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceReimburseListener implements FlowStateListener {

    /** 只处理 V38 预置的报销示例流程 */
    private static final String EXPENSE_FLOW_CODE = "expense-sample";

    private static final String PENDING = "PENDING";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";

    private final InvoiceRecordRepository invoiceRepo;

    @Override
    public boolean supports(String flowCode) {
        return EXPENSE_FLOW_CODE.equals(flowCode);
    }

    @Override
    @Transactional
    public void onFlowFinished(Long instanceId, String flowCode, String businessKey,
                               String state, String comment) {
        if (businessKey == null || businessKey.isBlank()) {
            log.warn("[reimburse] flow {} finished but businessKey empty, skip writeback", instanceId);
            return;
        }
        String target = switch (state) {
            case APPROVED -> APPROVED;
            case REJECTED -> REJECTED;
            // CANCELED 引擎尚未产生；真出现了也不该改报销状态
            default -> null;
        };
        if (target == null) {
            log.warn("[reimburse] flow {} finished with unexpected state={}, skip writeback", instanceId, state);
            return;
        }

        int n = invoiceRepo.updateReimburseStatusByBusinessKey(businessKey, target, PENDING);
        if (n == 0) {
            // 多半是这批单据早就不是 PENDING（比如被重复回调），不是错误，但要留痕
            log.warn("[reimburse] no PENDING invoice matched businessKey={} (flow={}, state={})",
                    businessKey, instanceId, state);
        } else {
            log.info("[reimburse] businessKey={} → {} ({} records, flow={}{})",
                    businessKey, target, n, instanceId,
                    comment == null || comment.isBlank() ? "" : ", comment=" + comment);
        }
    }
}