package com.anomalyco.ifinbrain.web.design.flow.dto;

import com.anomalyco.ifinbrain.web.design.flow.entity.SysFlowDefine;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FlowDefineDto {

    private Long id;
    private String code;
    private String name;
    private String description;
    private FlowJson flowJson;
    private String flowJsonRaw;
    private String defaultFormCode;
    private Short status;
    private Integer version;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 节点数（便捷字段，前端列表展示用） */
    private Integer nodeCount;
    /** 连线数 */
    private Integer edgeCount;

    public static FlowDefineDto from(SysFlowDefine e, FlowJson json, int nodes, int edges) {
        return FlowDefineDto.builder()
                .id(e.getId())
                .code(e.getCode())
                .name(e.getName())
                .description(e.getDescription())
                .flowJsonRaw(e.getFlowJson())
                .flowJson(json)
                .defaultFormCode(e.getDefaultFormCode())
                .status(e.getStatus())
                .version(e.getVersion())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .nodeCount(nodes)
                .edgeCount(edges)
                .build();
    }
}