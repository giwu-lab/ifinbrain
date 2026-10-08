package com.anomalyco.ifinbrain.web.dto.dept;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public class DeptReqs {

    /** 新建部门 */
    @Data
    public static class Create {
        @NotBlank
        @Size(max = 128)
        private String name;

        /** 上级部门 id；0 或空 = 根部门 */
        private Long parentId;

        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "code 只能含字母、数字、下划线和中划线")
        private String code;

        @Size(max = 64)
        private String leader;

        @Size(max = 32)
        private String phone;

        private Integer sort;

        private Short status;

        @Size(max = 512)
        private String remark;
    }

    /** 更新部门（全部字段可选，仅非 null 才覆盖 —— partial update） */
    @Data
    public static class Update {
        @Size(max = 128)
        private String name;

        private Long parentId;

        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "code 只能含字母、数字、下划线和中划线")
        private String code;

        @Size(max = 64)
        private String leader;

        @Size(max = 32)
        private String phone;

        private Integer sort;

        private Short status;

        @Size(max = 512)
        private String remark;
    }

    /**
     * 部门树节点。
     *
     * <p>同时带 label / value 别名：审批节点配置（ApprovalNodeConfig）与表单 DEPT 字段
     * 用 el-tree-select 消费本结构，不传 :props 时 Element Plus 读的是 label / value /
     * children / id 几个默认字段，缺一个就选不出东西。
     */
    @Data
    public static class Node {
        private Long id;
        private Long parentId;
        private String name;
        private String code;
        private String leader;
        private String phone;
        private Integer sort;
        private Short status;
        private String remark;
        private String createdAt;
        private String updatedAt;
        /** = name */
        private String label;
        /** = id */
        private Long value;
        private List<Node> children;
    }
}
