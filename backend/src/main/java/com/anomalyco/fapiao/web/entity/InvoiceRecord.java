package com.anomalyco.fapiao.web.entity;

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

    @Column(name = "uploader_id")
    private Long uploaderId;

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