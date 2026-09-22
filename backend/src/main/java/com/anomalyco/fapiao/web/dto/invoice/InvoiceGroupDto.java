package com.anomalyco.fapiao.web.dto.invoice;

import com.anomalyco.fapiao.web.entity.InvoiceRecord;
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

    private String verificationNo;
    private String verifier;
    private LocalDate verificationDate;

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