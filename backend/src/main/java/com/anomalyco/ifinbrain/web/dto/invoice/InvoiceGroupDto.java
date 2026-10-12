package com.anomalyco.ifinbrain.web.dto.invoice;

import com.anomalyco.ifinbrain.web.entity.InvoiceRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 发票列表分组视图：同一发票号码只显示一行，formats 字段给出各格式的有无与 storageKey。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceGroupDto {
    /** 分组键：发票号码；空字符串表示未识别（孤儿记录） */
    private String invoiceNumber;

    /** 该组所有入库记录的 id（编辑/删除时使用） */
    private java.util.List<Long> ids;

    /** 文件格式条目：key ∈ {"pdf","xml","ofd"}；值为 null 时该格式不存在 */
    private Map<String, FormatEntry> formats;

    // ---- 以下字段为该组共享信息：取自组内 id 最大的记录 ----

    private Long representativeId;

    private String invoiceType;

    private LocalDate issueDate;

    private String buyerName;
    private String buyerTaxId;

    private String sellerName;
    private String sellerTaxId;

    private BigDecimal amountWithoutTax;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String totalAmountUpper;

    private String payee;
    private String reviewer;
    private String drawer;

    private String message;

    /** 费用类别（fapiao_cn 的 expense_category_label）；老数据 / 未识别时为 null */
    private String expenseCategoryLabel;

    private String verificationNo;
    private String verifier;
    private LocalDate verificationDate;

    /**
     * 动态报销申请单的字段值（已从 form_data 的 JSON 串解析成对象）。
     *
     * <p>未填申请单的记录为 null —— 列里存 null 就出 null，不兜底成空对象，
     * 否则前端分不清「没填单」和「填了空单」。
     */
    private Map<String, Object> formData;

    /** 报销状态：NONE 未报销 / PENDING 审批中 / APPROVED 已报销 / REJECTED 已驳回 */
    private String reimburseStatus;

    /** 关联的审批流实例 id；未走流程时为 null。列表「查看流程」用它跳详情 */
    private Long flowInstanceId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 单个格式的存储条目 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormatEntry {
        private Long recordId;
        private String storageKey;
        private String originalFilename;
        private String sha256;
        private Long sizeBytes;
        private String invoiceNumber;

        public static FormatEntry from(InvoiceRecord r) {
            return FormatEntry.builder()
                    .recordId(r.getId())
                    .storageKey(r.getStorageKey())
                    .originalFilename(r.getOriginalFilename())
                    .sha256(r.getSha256())
                    .sizeBytes(r.getSizeBytes())
                    .invoiceNumber(r.getInvoiceNumber())
                    .build();
        }
    }
}