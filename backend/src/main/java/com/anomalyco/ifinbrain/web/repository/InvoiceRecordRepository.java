package com.anomalyco.ifinbrain.web.repository;

import com.anomalyco.ifinbrain.web.entity.InvoiceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public interface InvoiceRecordRepository extends JpaRepository<InvoiceRecord, Long> {

    Optional<InvoiceRecord> findFirstBySha256(String sha256);

    @Query("SELECT i FROM InvoiceRecord i WHERE " +
           "(:keyword IS NULL OR :keyword = '' OR i.invoiceNumber LIKE %:keyword% OR i.sellerName LIKE %:keyword% OR i.buyerName LIKE %:keyword%) AND " +
           "(:seller IS NULL OR :seller = '' OR i.sellerName LIKE %:seller%) AND " +
           "(:verificationNo IS NULL OR :verificationNo = '' OR i.verificationNo LIKE %:verificationNo%) AND " +
           "(:verifier IS NULL OR :verifier = '' OR i.verifier LIKE %:verifier%) " +
           "ORDER BY i.id DESC")
    List<InvoiceRecord> search(@Param("keyword") String keyword,
                               @Param("seller") String seller,
                               @Param("verificationNo") String verificationNo,
                               @Param("verifier") String verifier,
                               org.springframework.data.domain.Pageable pageable);

    /** 含核销日期范围条件的列表查询。from/to 任一为 null 表示该端不限（含边界）。 */
    @Query("SELECT i FROM InvoiceRecord i WHERE " +
           "(:keyword IS NULL OR :keyword = '' OR i.invoiceNumber LIKE %:keyword% OR i.sellerName LIKE %:keyword% OR i.buyerName LIKE %:keyword%) AND " +
           "(:seller IS NULL OR :seller = '' OR i.sellerName LIKE %:seller%) AND " +
           "(:verificationNo IS NULL OR :verificationNo = '' OR i.verificationNo LIKE %:verificationNo%) AND " +
           "(:verifier IS NULL OR :verifier = '' OR i.verifier LIKE %:verifier%) AND " +
           "(:dateFrom IS NULL OR i.verificationDate >= :dateFrom) AND " +
           "(:dateTo   IS NULL OR i.verificationDate <= :dateTo) " +
           "ORDER BY i.id DESC")
    List<InvoiceRecord> searchWithDateRange(@Param("keyword") String keyword,
                                            @Param("seller") String seller,
                                            @Param("verificationNo") String verificationNo,
                                            @Param("verifier") String verifier,
                                            @Param("dateFrom") LocalDate dateFrom,
                                            @Param("dateTo") LocalDate dateTo,
                                            org.springframework.data.domain.Pageable pageable);

    /** 与 searchWithDateRange 同条件的总数查询（用于准确分页）。 */
    @Query("SELECT COUNT(i) FROM InvoiceRecord i WHERE " +
           "(:keyword IS NULL OR :keyword = '' OR i.invoiceNumber LIKE %:keyword% OR i.sellerName LIKE %:keyword% OR i.buyerName LIKE %:keyword%) AND " +
           "(:seller IS NULL OR :seller = '' OR i.sellerName LIKE %:seller%) AND " +
           "(:verificationNo IS NULL OR :verificationNo = '' OR i.verificationNo LIKE %:verificationNo%) AND " +
           "(:verifier IS NULL OR :verifier = '' OR i.verifier LIKE %:verifier%) AND " +
           "(:dateFrom IS NULL OR i.verificationDate >= :dateFrom) AND " +
           "(:dateTo   IS NULL OR i.verificationDate <= :dateTo)")
    long countWithDateRange(@Param("keyword") String keyword,
                            @Param("seller") String seller,
                            @Param("verificationNo") String verificationNo,
                            @Param("verifier") String verifier,
                            @Param("dateFrom") LocalDate dateFrom,
                            @Param("dateTo") LocalDate dateTo);

    /** 游标导出：native query + 手工 ResultSet 映射。
     *  直接拿 Object[]，逐列按位置读 String / Number / Timestamp，自己装配成 InvoiceRecord，
     *  避开 Hibernate 对 DATE 列的类型转换（历史上 sqlite-jdbc 的 FastDateParser 会在
     *  "YYYY-MM-DD" 上抛 Unparseable date）。SQLite 已弃用，但 PG 的 pgjdbc 同样可能按
     *  java.sql.Date 而非 java.time.LocalDate 返回，手工映射两边都吃得下，故保留。
     *  注意：本查询用 '||' 拼串（PG 与 SQLite 通用语法），不是 PG 专属写法。 */
    @Query(value = "SELECT id, storage_key, storage_type, original_filename, size_bytes, sha256, " +
                   "       invoice_type, invoice_number, issue_date, buyer_name, buyer_tax_id, " +
                   "       seller_name, seller_tax_id, amount_without_tax, tax_amount, total_amount, " +
                   "       total_amount_upper, payee, reviewer, drawer, message, uploader_id, " +
                   "       created_at, updated_at, verification_no, verifier, verification_date, " +
                   "       expense_category_label " +
                   "FROM invoice_record i WHERE " +
                   "(:keyword IS NULL OR :keyword = '' OR i.invoice_number LIKE '%' || :keyword || '%' OR i.seller_name LIKE '%' || :keyword || '%' OR i.buyer_name LIKE '%' || :keyword || '%') AND " +
                   "(:seller IS NULL OR :seller = '' OR i.seller_name LIKE '%' || :seller || '%') AND " +
                   "(:verificationNo IS NULL OR :verificationNo = '' OR i.verification_no LIKE '%' || :verificationNo || '%') AND " +
                   "(:verifier IS NULL OR :verifier = '' OR i.verifier LIKE '%' || :verifier || '%') AND " +
                   "i.id < :lastId " +
                   "ORDER BY i.id DESC LIMIT :limit",
           nativeQuery = true)
    List<Object[]> cursorSearchRaw(@Param("keyword") String keyword,
                                   @Param("seller") String seller,
                                   @Param("verificationNo") String verificationNo,
                                   @Param("verifier") String verifier,
                                   @Param("lastId") long lastId,
                                   @Param("limit") int limit);

    /** 把 cursorSearchRaw 的 Object[] 行映射成 InvoiceRecord。 */
    default List<InvoiceRecord> cursorSearch(String keyword, String seller, String verificationNo,
                                             String verifier, long lastId, int limit) {
        List<Object[]> rows = cursorSearchRaw(emptyToNull(keyword), emptyToNull(seller),
                emptyToNull(verificationNo), emptyToNull(verifier), lastId, limit);
        List<InvoiceRecord> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            InvoiceRecord i = new InvoiceRecord();
            i.setId(asLong(r[0]));
            i.setStorageKey(asString(r[1]));
            i.setStorageType(asString(r[2]));
            i.setOriginalFilename(asString(r[3]));
            i.setSizeBytes(asLong(r[4]));
            i.setSha256(asString(r[5]));
            i.setInvoiceType(asString(r[6]));
            i.setInvoiceNumber(asString(r[7]));
            i.setIssueDate(asLocalDate(r[8]));
            i.setBuyerName(asString(r[9]));
            i.setBuyerTaxId(asString(r[10]));
            i.setSellerName(asString(r[11]));
            i.setSellerTaxId(asString(r[12]));
            i.setAmountWithoutTax(asBigDecimal(r[13]));
            i.setTaxAmount(asBigDecimal(r[14]));
            i.setTotalAmount(asBigDecimal(r[15]));
            i.setTotalAmountUpper(asString(r[16]));
            i.setPayee(asString(r[17]));
            i.setReviewer(asString(r[18]));
            i.setDrawer(asString(r[19]));
            i.setMessage(asString(r[20]));
            i.setUploaderId(asLong(r[21]));
            i.setCreatedAt(asLocalDateTime(r[22]));
            i.setUpdatedAt(asLocalDateTime(r[23]));
            i.setVerificationNo(asString(r[24]));
            i.setVerifier(asString(r[25]));
            i.setVerificationDate(asLocalDate(r[26]));
            i.setExpenseCategoryLabel(asString(r[27]));   // 追加列，固定在末尾
            out.add(i);
        }
        return out;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static String asString(Object o) { return o == null ? null : String.valueOf(o); }
    private static Long asLong(Object o) { return o == null ? null : ((Number) o).longValue(); }
    private static java.math.BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof java.math.BigDecimal b) return b;
        // 保留原精度：避免 998.0 这种浮点痕迹
        return new java.math.BigDecimal(String.valueOf(o));
    }
    private static LocalDate asLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        String str = String.valueOf(o);
        // PG 的 pgjdbc 可能返回 LocalDate、java.sql.Date 或 "2026-01-01" 三种形态
        if (str.length() >= 10) return LocalDate.parse(str.substring(0, 10));
        return null;
    }
    private static LocalDateTime asLocalDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime t) return t;
        String str = String.valueOf(o);
        if (str.length() >= 19) return LocalDateTime.parse(str.substring(0, 19).replace(' ', 'T'));
        return null;
    }

    /** 全库查重源数据：invoiceNumber 非空，按 id DESC，上限 limit
     * @deprecated 5000 截断方案已被 SQL GROUP BY 替代；保留仅为兼容旧调用 */
    @Deprecated
    @Query("SELECT i FROM InvoiceRecord i WHERE " +
           "(i.invoiceNumber IS NOT NULL AND i.invoiceNumber <> '') " +
           "ORDER BY i.id DESC")
    List<InvoiceRecord> findAllForDuplicateCheck(org.springframework.data.domain.Pageable pageable);

    /** 按发票号集合查询存在的记录（invoices 任意一个匹配即返回） */
    @Query("SELECT i FROM InvoiceRecord i WHERE " +
           "i.invoiceNumber IN :invoiceNumbers " +
           "ORDER BY i.invoiceNumber ASC, i.id DESC")
    List<InvoiceRecord> findByInvoiceNumberIn(@Param("invoiceNumbers") java.util.Collection<String> invoiceNumbers);

    /* ===================== 方案 C：SQL GROUP BY 替代内存分组 ===================== */

    /** 查询所有「有重复嫌疑」的发票号（同号 ≥2 条）。只返回字符串列，内存开销小。 */
    @Query(value = "SELECT invoice_number FROM invoice_record " +
                   "WHERE invoice_number IS NOT NULL AND invoice_number <> '' " +
                   "GROUP BY invoice_number HAVING COUNT(*) > 1 " +
                   "ORDER BY MAX(id) DESC",
           nativeQuery = true)
    List<String> findDuplicateInvoiceNumbers();

    /** 按发票号查询该号下全部记录（明细行数小，可内存处理）。按 id ASC 方便后续保留最早。 */
    @Query("SELECT i FROM InvoiceRecord i WHERE i.invoiceNumber = :no ORDER BY i.id ASC")
    List<InvoiceRecord> findByInvoiceNumberOrderByIdAsc(@Param("no") String invoiceNumber);

    /**
     * 分组列表（真·服务器端分页）：
     * 分组键 = COALESCE(NULLIF(invoice_number,''), '__orphan__#'||id)， orphan 各自成组。
     * 返回 [gkey, rep_id, cnt]，rep_id 是组内 MAX(id)。
     *
     * 筛选条件与 search 一致 + 可选核销日期范围。
     */
    @Query(value = "SELECT " +
                   "  COALESCE(NULLIF(invoice_number,''), '__orphan__#' || CAST(id AS TEXT)) AS gkey, " +
                   "  MAX(id) AS rep_id, " +
                   "  COUNT(*) AS cnt " +
                   "FROM invoice_record " +
                   "WHERE (:keyword IS NULL OR :keyword = '' OR invoice_number LIKE '%' || :keyword || '%' OR seller_name LIKE '%' || :keyword || '%' OR buyer_name LIKE '%' || :keyword || '%') " +
                   "  AND (:seller IS NULL OR :seller = '' OR seller_name LIKE '%' || :seller || '%') " +
                   "  AND (:verificationNo IS NULL OR :verificationNo = '' OR verification_no LIKE '%' || :verificationNo || '%') " +
                   "  AND (:verifier IS NULL OR :verifier = '' OR verifier LIKE '%' || :verifier || '%') " +
                   "  AND (:dateFrom IS NULL OR verification_date >= :dateFrom) " +
                   "  AND (:dateTo   IS NULL OR verification_date <= :dateTo) " +
                   "GROUP BY gkey " +
                   "ORDER BY rep_id DESC " +
                   "LIMIT :size OFFSET :offset",
           nativeQuery = true)
    List<Object[]> listGroupKeys(@Param("keyword") String keyword,
                                 @Param("seller") String seller,
                                 @Param("verificationNo") String verificationNo,
                                 @Param("verifier") String verifier,
                                 @Param("dateFrom") LocalDate dateFrom,
                                 @Param("dateTo") LocalDate dateTo,
                                 @Param("size") int size,
                                 @Param("offset") int offset);

    /** 与 listGroupKeys 同条件的总组数（用于分页器显示）。 */
    @Query(value = "SELECT COUNT(*) FROM (" +
                   "  SELECT COALESCE(NULLIF(invoice_number,''), '__orphan__#' || CAST(id AS TEXT)) AS gkey " +
                   "  FROM invoice_record " +
                   "  WHERE (:keyword IS NULL OR :keyword = '' OR invoice_number LIKE '%' || :keyword || '%' OR seller_name LIKE '%' || :keyword || '%' OR buyer_name LIKE '%' || :keyword || '%') " +
                   "    AND (:seller IS NULL OR :seller = '' OR seller_name LIKE '%' || :seller || '%') " +
                   "    AND (:verificationNo IS NULL OR :verificationNo = '' OR verification_no LIKE '%' || :verificationNo || '%') " +
                   "    AND (:verifier IS NULL OR :verifier = '' OR verifier LIKE '%' || :verifier || '%') " +
                   "    AND (:dateFrom IS NULL OR verification_date >= :dateFrom) " +
                   "    AND (:dateTo   IS NULL OR verification_date <= :dateTo) " +
                   "  GROUP BY gkey" +
                   ")",
           nativeQuery = true)
    long countGroupKeys(@Param("keyword") String keyword,
                        @Param("seller") String seller,
                        @Param("verificationNo") String verificationNo,
                        @Param("verifier") String verifier,
                        @Param("dateFrom") LocalDate dateFrom,
                        @Param("dateTo") LocalDate dateTo);

    /** 按 id 集合批量查明细（用于 listGrouped 的第二步：拿到 rep_id 列表后一次性查询）。 */
    @Query("SELECT i FROM InvoiceRecord i WHERE i.id IN :ids")
    List<InvoiceRecord> findAllByIdIn(@Param("ids") java.util.Collection<Long> ids);

    /**
     * 按报销单号改报销状态 —— 审批流回写用。
     *
     * <p>一次 UPDATE 覆盖该报销单下的**全部**记录（含同一发票号的 PDF/XML/OFD 多条）：
     * 列表是按发票号分组的，只改代表行会让同组其它行状态不一致。
     *
     * <p>限定 {@code oldStatus = 'PENDING'}：避免把已经定终态的单据被重复回调改回去。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE InvoiceRecord i SET i.reimburseStatus = :newStatus WHERE i.verificationNo = :businessKey AND i.reimburseStatus = :oldStatus")
    int updateReimburseStatusByBusinessKey(@Param("businessKey") String businessKey,
                                           @Param("newStatus") String newStatus,
                                           @Param("oldStatus") String oldStatus);

    /**
     * 按报销单号回填流程实例 id —— 同样是批量 UPDATE，不能走实体的脏检查。
     *
     * <p>原因：流程启动后可能**当场**跑完（start 节点直连 END 就会如此），
     * {@link com.anomalyco.ifinbrain.web.service.InvoiceReimburseListener} 会立刻把
     * 状态批量刷成 APPROVED；随后若再通过实体 setter 写 flowInstanceId，
     * Hibernate 的脏检查会发出「全列 UPDATE」，把内存里过期的 PENDING 写回去，
     * 把监听器的回写结果覆盖掉。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE InvoiceRecord i SET i.flowInstanceId = :instanceId WHERE i.verificationNo = :businessKey")
    int updateFlowInstanceIdByBusinessKey(@Param("businessKey") String businessKey,
                                          @Param("instanceId") Long instanceId);
}