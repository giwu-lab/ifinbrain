package com.anomalyco.ifinbrain.web.design.flow.dto;

import com.anomalyco.ifinbrain.web.design.flow.enums.ApproveType;
import com.anomalyco.ifinbrain.web.design.flow.enums.NodeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 节点业务数据。
 * 与 mfish NodeData 兼容 + 增加 formCode 字段（iFinBrain 扩展：每个节点可选挂表单）。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NodeData {

    private NodeType type;
    private String label;
    private String description;
    private List<String> userIds;
    private List<String> roleIds;
    private List<String> orgIds;
    private ApproveType approvalType;
    /** 仅 PERCENT 模式有效，0-100 */
    private Integer completionPercent;
    /** 仅 CONDITION 节点用 */
    private List<Branch> branches;
    /** 本节点挂载的表单 code（覆盖流程默认表单） */
    private String formCode;
}