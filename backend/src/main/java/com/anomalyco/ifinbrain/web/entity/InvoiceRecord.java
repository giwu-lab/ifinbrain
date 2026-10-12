package com.anomalyco.ifinbrain.web.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "invoice_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "storage_type", nullable = false, length = 16)
    private String storageType;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "invoice_type", length = 128)
    private String invoiceType;

    @Column(name = "invoice_number", length = 64)
    private String invoiceNumber;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "buyer_name", length = 255)
    private String buyerName;

    @Column(name = "buyer_tax_id", length = 64)
    private String buyerTaxId;

    @Column(name = "seller_name", length = 255)
    private String sellerName;

    @Column(name = "seller_tax_id", length = 64)
    private String sellerTaxId;

    @Column(name = "amount_without_tax", precision = 18, scale = 2)
    private BigDecimal amountWithoutTax;

    @Column(name = "tax_amount", precision = 18, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "total_amount_upper", length = 255)
    private String totalAmountUpper;

    @Column(length = 64)
    private String payee;

    @Column(length = 64)
    private String reviewer;

    @Column(length = 64)
    private String drawer;

    @Column(length = 255)
    private String message;

    @Column(name = "verification_no", length = 64)
    private String verificationNo;

    @Column(name = "verifier", length = 64)
    private String verifier;

    @Column(name = "verification_date")
    private LocalDate verificationDate;

    /**
     * 费用类别 —— 来自 fapiao_cn 识别的 {@code expense_category_label}（中文可读文案）。
     *
     * <p>识别接口未配置 / 老版本解析器 / 识别失败时为 null，不兜底空串。
     */
    @Column(name = "expense_category_label", length = 128)
    private String expenseCategoryLabel;

    @Column(name = "uploader_id")
    private Long uploaderId;

    /**
     * 报销申请单字段值（JSON 字符串，key = 表单字段 key）。
     *
     * <p>存 TEXT 而非 JSONB，与 {@code sys_form_design.fields_json} /
     * {@code sys_flow_instance.variables_json} 全库惯例一致。
     *
     * <p>{@link JsonIgnore}：裸 JSON 串不该出现在接口响应里 —— 需要时由
     * {@code InvoiceGroupDto} 解析成 Map 再出参，调用方拿到的始终是对象。
     */
    @JsonIgnore
    @Column(name = "form_data", columnDefinition = "TEXT")
    private String formDataJson;

    /**
     * 报销状态：NONE 未报销 / PENDING 审批中 / APPROVED 已报销 / REJECTED 已驳回。
     *
     * <p>不能靠「报销单号非空」判定已报销 —— 单号在提交瞬间就写入了（它同时是
     * storageKey 的目录层级），而开关打开时提交只是发起审批，真正完成要等流程通过。
     */
    @Column(name = "reimburse_status", nullable = false, length = 16)
    private String reimburseStatus;

    /** 关联的审批流实例 id；未走流程时为 null */
    @Column(name = "flow_instance_id")
    private Long flowInstanceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}