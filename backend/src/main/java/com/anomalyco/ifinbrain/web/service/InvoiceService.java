package com.anomalyco.ifinbrain.web.service;

import com.anomalyco.ifinbrain.web.client.FapiaoCnClient;
import com.anomalyco.ifinbrain.web.client.FapiaoCnClient.ExtractItem;
import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.common.InvoiceExport;
import com.anomalyco.ifinbrain.web.common.PageReq;
import com.anomalyco.ifinbrain.web.dto.invoice.InvoiceGroupDto;
import com.anomalyco.ifinbrain.web.entity.InvoiceRecord;
import com.anomalyco.ifinbrain.web.repository.InvoiceRecordRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.storage.StorageSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRecordRepository invoiceRepo;
    private final FapiaoCnClient fapiaoClient;
    private final StorageSelector storage;
    private final PermissionChecker perm;

    private static final DateTimeFormatter KEY_TS = DateTimeFormatter.ofPattern("yyyy/MM/");

    /** 发票正式落库路径前缀 —— 根目录/fapiao/（核销单号）/文件名，取消年/月归集。 */
    private static final String INVOICE_KEY_PREFIX = "fapiao/";

    @Transactional(readOnly = true)
    public Page<InvoiceRecord> list(PageReq req) {
        perm.require("invoice:list");
        var pageable = req.toPageable();
        LocalDate[] range = parseDateRange(req.getVerificationDateFrom(), req.getVerificationDateTo());
        String keyword = emptyToNull(req.getKeyword());
        String seller = emptyToNull(req.getSeller());
        String verificationNo = emptyToNull(req.getVerificationNo());
        String verifier = emptyToNull(req.getVerifier());
        // 日期条件下推到 SQL + 单独的 COUNT 查询，保证 total 是全库匹配的真实行数。
        // 历史 bug：旧版先 DB 分页再内存过滤，把 items.size() 当 total 返回，导致分页器显示错误。
        List<InvoiceRecord> items = invoiceRepo.searchWithDateRange(
                keyword, seller, verificationNo, verifier, range[0], range[1], pageable);
        long total = invoiceRepo.countWithDateRange(
                keyword, seller, verificationNo, verifier, range[0], range[1]);
        return new PageImpl<>(items, pageable, total);
    }

    /**
 * 流式导出：游标分批 (id DESC, size=1000) + 直接写入 OutputStream，
 * JVM 堆峰值与数据总量无关。客户端断流时抛 IOException，安全退出。
 *
 * 调用方需保证 SecurityContext 已设置（Controller 在 lambda 内传播）。
 *
 * kind: "csv" | "xlsx"
 */
@Transactional(readOnly = true)
public void streamExport(PageReq req, OutputStream out, String kind) throws IOException {
    perm.require("invoice:list");
    LocalDate[] range = parseDateRange(req.getVerificationDateFrom(), req.getVerificationDateTo());
    final int batchSize = 1000;
    long lastId = Long.MAX_VALUE;

    if ("xlsx".equalsIgnoreCase(kind)) {
        try (SXSSFWorkbook wb = InvoiceExport.initXlsx()) {
            Sheet sheet = wb.getSheet("Invoices");
            int rIdx = 1;
            while (true) {
                List<InvoiceRecord> rows = invoiceRepo.cursorSearch(
                        emptyToNull(req.getKeyword()),
                        emptyToNull(req.getSeller()),
                        emptyToNull(req.getVerificationNo()),
                        emptyToNull(req.getVerifier()),
                        lastId,
                        batchSize);
                if (rows.isEmpty()) break;
                for (InvoiceRecord r : rows) {
                    if (!inDateRange(r, range)) continue;
                    InvoiceExport.appendXlsxRow(sheet, rIdx++, r);
                }
                lastId = rows.get(rows.size() - 1).getId();
                if (rows.size() < batchSize) break;
            }
            wb.write(out);
            out.flush();
        }
    } else {
        InvoiceExport.writeCsvHeader(out);
        while (true) {
            List<InvoiceRecord> rows = invoiceRepo.cursorSearch(
                    emptyToNull(req.getKeyword()),
                    emptyToNull(req.getSeller()),
                    emptyToNull(req.getVerificationNo()),
                    emptyToNull(req.getVerifier()),
                    lastId,
                    batchSize);
            if (rows.isEmpty()) break;
            for (InvoiceRecord r : rows) {
                if (!inDateRange(r, range)) continue;
                InvoiceExport.appendCsvRow(out, r);
            }
            lastId = rows.get(rows.size() - 1).getId();
            if (rows.size() < batchSize) break;
        }
        out.flush();
    }
}

/** 内存日期范围检查（与 list() 一致）。 */
private boolean inDateRange(InvoiceRecord r, LocalDate[] range) {
    LocalDate from = range[0];
    LocalDate to = range[1];
    if (from == null && to == null) return true;
    LocalDate d = r.getVerificationDate();
    if (d == null) return false;
    if (from != null && d.isBefore(from)) return false;
    if (to != null && d.isAfter(to)) return false;
    return true;
}

/** 保留供测试 / 旧调用方使用。新代码请走 streamExport。 */
@Transactional(readOnly = true)
public List<InvoiceRecord> exportAll(PageReq req) {
    perm.require("invoice:list");
    List<InvoiceRecord> all = new ArrayList<>();
    int page = 1;
    int size = 500;
    while (true) {
        PageReq sub = new PageReq();
        sub.setPage(page);
        sub.setSize(size);
        sub.setKeyword(req.getKeyword());
        sub.setSeller(req.getSeller());
        sub.setVerificationNo(req.getVerificationNo());
        sub.setVerifier(req.getVerifier());
        sub.setVerificationDateFrom(req.getVerificationDateFrom());
        sub.setVerificationDateTo(req.getVerificationDateTo());
        var p = list(sub);
        var items = p.getContent();
        if (items.isEmpty()) break;
        all.addAll(items);
        if (items.size() < size) break;
        page++;
        if (page > 1000) break;
    }
    return all;
}

    /** 全库查重：按 (invoiceNumber, fileExt) 分组，组内 ≥ 2 视为重复。
     *  不按 verificationNo / verifier 过滤——跨核销单号也属重复。
     *
     *  方案 C：SQL GROUP BY 找出有重复的发票号，再逐号查明细（单号下记录数小），
     *  彻底去掉 5000 上限。内存压力只与「有重复的发票号数量」相关，而非全库行数。
     *
     *  @param limit 历史上用于截断；方案 C 后不再截断，参数保留仅为 API 兼容，实际忽略
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findDuplicates(int limit) {
        perm.require("invoice:list");
        // 第一步：SQL 找出所有有重复的发票号（仅一列字符串，内存安全）
        List<String> dupNumbers = invoiceRepo.findDuplicateInvoiceNumbers();

        // 第二步：逐号查明细，在 Java 里按 (no, ext) 细分
        List<Map<String, Object>> out = new ArrayList<>();
        for (String no : dupNumbers) {
            List<InvoiceRecord> group = invoiceRepo.findByInvoiceNumberOrderByIdAsc(no);
            // 按 ext 细分（保留原有 (no, ext) 键语义）
            Map<String, List<InvoiceRecord>> byExt = new LinkedHashMap<>();
            for (InvoiceRecord r : group) {
                String ext = fileExtension(r.getOriginalFilename());
                byExt.computeIfAbsent(ext, k -> new ArrayList<>()).add(r);
            }
            for (var e : byExt.entrySet()) {
                List<InvoiceRecord> arr = e.getValue();
                if (arr.size() < 2) continue;
                String ext = e.getKey();
                Map<String, Object> g = new HashMap<>();
                g.put("key", no + "::" + ext);
                g.put("invoiceNumber", no);
                g.put("fileExt", ext);
                g.put("count", arr.size());
                // 按 id DESC（保持旧行为）
                arr.sort(Comparator.comparing(InvoiceRecord::getId).reversed());
                g.put("records", arr);
                out.add(g);
            }
        }
        // 重复组按出现次数降序（保持旧行为）
        out.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));
        return out;
    }

    /**
     * 清理按 (发票号码, 文件格式) 重复的记录：每组保留 id 最小（最早入库）的一条，
     * 删除其余记录 + 对应的 storage 文件。
     *
     * 方案 C：先 SQL 查所有重复发票号，再逐号分批处理，去掉 5000 截断。
     *
     * @return { rowsDeleted, filesDeleted, groupsCleaned, deleted: [...] }
     */
    @Transactional
    public Map<String, Object> dedupe() {
        perm.require("invoice:delete");
        List<String> dupNumbers = invoiceRepo.findDuplicateInvoiceNumbers();

        int rowsDeleted = 0;
        int filesDeleted = 0;
        int filesMissing = 0;
        int groupsCleaned = 0;
        List<Map<String, Object>> deleted = new ArrayList<>();

        for (String no : dupNumbers) {
            List<InvoiceRecord> group = invoiceRepo.findByInvoiceNumberOrderByIdAsc(no);
            // 按 ext 细分（保持原 (no, ext) 键语义）
            Map<String, List<InvoiceRecord>> byExt = new LinkedHashMap<>();
            for (InvoiceRecord r : group) {
                String ext = fileExtension(r.getOriginalFilename());
                byExt.computeIfAbsent(ext, k -> new ArrayList<>()).add(r);
            }
            for (var e : byExt.entrySet()) {
                List<InvoiceRecord> recs = e.getValue();
                if (recs.size() < 2) continue;
                // recs 已按 id ASC 排序（findByInvoiceNumberOrderByIdAsc），保留最早一条
                InvoiceRecord kept = recs.get(0);
                List<InvoiceRecord> toDelete = recs.subList(1, recs.size());
                groupsCleaned++;

                for (InvoiceRecord r : toDelete) {
                    String key = r.getStorageKey();
                    if (key != null && !key.isBlank()) {
                        try {
                            storage.current().delete(key);
                            filesDeleted++;
                        } catch (Exception ex) {
                            log.warn("dedupe: delete file {} failed: {}", key, ex.getMessage());
                            filesMissing++;
                        }
                    }
                    invoiceRepo.delete(r);
                    rowsDeleted++;

                    Map<String, Object> info = new HashMap<>();
                    info.put("id", r.getId());
                    info.put("invoiceNumber", r.getInvoiceNumber());
                    info.put("fileExt", fileExtension(r.getOriginalFilename()));
                    info.put("originalFilename", r.getOriginalFilename());
                    info.put("storageKey", r.getStorageKey());
                    info.put("verificationNo", r.getVerificationNo());
                    info.put("keptId", kept.getId());
                    info.put("keptVerificationNo", kept.getVerificationNo());
                    deleted.add(info);
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("rowsDeleted", rowsDeleted);
        result.put("filesDeleted", filesDeleted);
        result.put("filesMissing", filesMissing);
        result.put("groupsCleaned", groupsCleaned);
        result.put("deleted", deleted);
        log.info("dedupe: cleaned {} groups, {} rows, {} files ({} missing)",
                groupsCleaned, rowsDeleted, filesDeleted, filesMissing);
        return result;
    }

    /**
     * 列出「传入的发票号集合中已存在于数据库」的记录（用于上传页面提示）。
     * 不限制 count ≥ 2——只要数据库里有该发票号就返回。
     */
    @Transactional(readOnly = true)
    public List<InvoiceRecord> findExistingByNumbers(java.util.Collection<String> invoiceNumbers) {
        perm.require("invoice:list");
        if (invoiceNumbers == null || invoiceNumbers.isEmpty()) return java.util.Collections.emptyList();
        List<String> cleaned = invoiceNumbers.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (cleaned.isEmpty()) return java.util.Collections.emptyList();
        return invoiceRepo.findByInvoiceNumberIn(cleaned);
    }

    private static String fileExtension(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i <= 0 || i == name.length() - 1) return "";
        String e = name.substring(i + 1).toLowerCase();
        return e.matches("[a-z0-9]{1,5}") ? e : "";
    }

    /**
     * 分组列表：按发票号码分组，每组只返回一行 + 各格式条目。
     * 共享字段取组内 id 最大的记录。
     *
     * 方案 C：真正的服务器端分页。
     *   第一步 SQL 用 GROUP BY 拿到当前页的组键 + 代表 id + 组大小；
     *   第二步按组键批量查明细，填充 formats 和 ids。
     * 内存开销只与当前页大小相关，不再有 5000 截断。
     *
     * @param limit 历史参数，方案 C 后忽略（保留兼容 controller 签名）
     */
    @Transactional(readOnly = true)
    public Page<InvoiceGroupDto> listGrouped(PageReq req, int limit) {
        perm.require("invoice:list");
        LocalDate[] range = parseDateRange(req.getVerificationDateFrom(), req.getVerificationDateTo());
        String keyword = emptyToNull(req.getKeyword());
        String seller = emptyToNull(req.getSeller());
        String verificationNo = emptyToNull(req.getVerificationNo());
        String verifier = emptyToNull(req.getVerifier());

        // 第一步：当前页的组键
        List<Object[]> groupRows = invoiceRepo.listGroupKeys(
                keyword, seller, verificationNo, verifier,
                range[0], range[1],
                req.getSize(), req.getOffset());
        long total = invoiceRepo.countGroupKeys(
                keyword, seller, verificationNo, verifier, range[0], range[1]);

        if (groupRows.isEmpty()) {
            return new PageImpl<>(List.of(), req.toPageable(), total);
        }

        // 第二步：按组键批量查明细
        // 分组键规则：非 orphan = invoice_number；orphan = "__orphan__#<id>"
        List<String> invoiceNumbers = new ArrayList<>();
        List<Long> orphanIds = new ArrayList<>();
        Map<String, Long> gkeyToRepId = new LinkedHashMap<>();
        Map<String, Integer> gkeyToCnt = new HashMap<>();
        for (Object[] row : groupRows) {
            String gkey = (String) row[0];
            long repId = ((Number) row[1]).longValue();
            int cnt = ((Number) row[2]).intValue();
            gkeyToRepId.put(gkey, repId);
            gkeyToCnt.put(gkey, cnt);
            if (gkey.startsWith("__orphan__#")) {
                orphanIds.add(Long.parseLong(gkey.substring("__orphan__#".length())));
            } else {
                invoiceNumbers.add(gkey);
            }
        }

        // 非 orphan 组：批量查明细
        Map<String, List<InvoiceRecord>> detailsByNo = new HashMap<>();
        if (!invoiceNumbers.isEmpty()) {
            List<InvoiceRecord> all = invoiceRepo.findByInvoiceNumberIn(invoiceNumbers);
            for (InvoiceRecord r : all) {
                detailsByNo.computeIfAbsent(r.getInvoiceNumber(), k -> new ArrayList<>()).add(r);
            }
        }
        // orphan 组：每组只有一条，批量按 id 查
        Map<Long, InvoiceRecord> orphanById = new HashMap<>();
        if (!orphanIds.isEmpty()) {
            for (InvoiceRecord r : invoiceRepo.findAllByIdIn(orphanIds)) {
                orphanById.put(r.getId(), r);
            }
        }

        // 第三步：按当前页顺序装配 DTO
        List<InvoiceGroupDto> page = new ArrayList<>(groupRows.size());
        for (Map.Entry<String, Long> e : gkeyToRepId.entrySet()) {
            String gkey = e.getKey();
            long repId = e.getValue();
            List<InvoiceRecord> recs;
            if (gkey.startsWith("__orphan__#")) {
                InvoiceRecord single = orphanById.get(repId);
                recs = single == null ? List.of() : List.of(single);
            } else {
                recs = detailsByNo.getOrDefault(gkey, List.of());
            }
            if (recs.isEmpty()) continue;

            // 共享字段：取 id 最大者（= repId 对应的记录）
            InvoiceRecord rep = recs.stream()
                    .max(Comparator.comparing(InvoiceRecord::getId))
                    .orElseThrow();
            List<Long> ids = recs.stream().map(InvoiceRecord::getId).sorted().toList();

            Map<String, InvoiceGroupDto.FormatEntry> fmts = new java.util.HashMap<>();
            fmts.put("pdf", null);
            fmts.put("xml", null);
            fmts.put("ofd", null);
            for (InvoiceRecord r : recs) {
                String ext = fileExtension(r.getOriginalFilename());
                if (!ext.isEmpty() && fmts.containsKey(ext) && fmts.get(ext) == null) {
                    fmts.put(ext, InvoiceGroupDto.FormatEntry.from(r));
                }
            }

            String displayNo = (rep.getInvoiceNumber() == null || rep.getInvoiceNumber().isBlank())
                    ? "（未识别）#" + rep.getId()
                    : rep.getInvoiceNumber();

            InvoiceGroupDto dto = InvoiceGroupDto.builder()
                    .invoiceNumber(displayNo)
                    .ids(ids)
                    .representativeId(rep.getId())
                    .formats(fmts)
                    .invoiceType(rep.getInvoiceType())
                    .issueDate(rep.getIssueDate())
                    .buyerName(rep.getBuyerName())
                    .buyerTaxId(rep.getBuyerTaxId())
                    .sellerName(rep.getSellerName())
                    .sellerTaxId(rep.getSellerTaxId())
                    .amountWithoutTax(rep.getAmountWithoutTax())
                    .taxAmount(rep.getTaxAmount())
                    .totalAmount(rep.getTotalAmount())
                    .totalAmountUpper(rep.getTotalAmountUpper())
                    .payee(rep.getPayee())
                    .reviewer(rep.getReviewer())
                    .drawer(rep.getDrawer())
                    .message(rep.getMessage())
                    .verificationNo(rep.getVerificationNo())
                    .verifier(rep.getVerifier())
                    .verificationDate(rep.getVerificationDate())
                    .createdAt(rep.getCreatedAt())
                    .updatedAt(rep.getUpdatedAt())
                    .build();
            page.add(dto);
        }

        return new PageImpl<>(page, req.toPageable(), total);
    }

    @Transactional(readOnly = true)
    public InvoiceRecord detail(Long id) {
        perm.require("invoice:view");
        return invoiceRepo.findById(id).orElseThrow(() -> new BizException(404, "Invoice not found"));
    }

    /**
     * 上传 + OCR：仅做识别与临时存储，**不落库**。返回的是 PendingInvoice（带 sha + 临时 key），
     * 前端展示两表，用户点击「保存」再走 commit()。
     */
    @Transactional
    public List<PendingInvoice> uploadAndRecognize(MultipartFile[] files, Long uploaderId) {
        perm.require("invoice:upload");
        if (files == null || files.length == 0) throw new BizException(400, "No files");
        int max = Math.min(files.length, 50);
        List<FilePart> toCall = new ArrayList<>();
        List<PendingInvoice> pendings = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            MultipartFile mf = files[i];
            if (mf.isEmpty()) continue;
            try {
                byte[] data = mf.getBytes();
                String sha = sha256(data);
                String orig = mf.getOriginalFilename() == null ? "file" : mf.getOriginalFilename();
                String tempKey = "_tmp/" + KEY_TS.format(LocalDateTime.now()) + sha + "-" + safeName(orig);
                storage.current().put(tempKey, new ByteArrayInputStream(data), data.length,
                        mf.getContentType() == null ? "application/octet-stream" : mf.getContentType());
                toCall.add(new FilePart(orig, data, sha, tempKey));
                pendings.add(new PendingInvoice(orig, sha, tempKey, data.length, uploaderId));
            } catch (Exception ex) {
                // 之前是 log.warn + skip，导致用户上传 5 个文件看到 3 个 pending；
                // 改成抛错（atomic 语义：要么全成要么全失败），前端可重试。
                log.error("upload: storage put failed for {}: {}",
                        mf.getOriginalFilename(), ex.getMessage(), ex);
                throw new BizException(500,
                        "上传到存储失败：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())
                        + "。请检查存储配置（S3 endpoint/bucket/AK/SK）后重试。");
            }
        }

        List<ExtractItem> results = new ArrayList<>();
        if (!toCall.isEmpty()) {
            int batchSize = 50;
            for (int i = 0; i < toCall.size(); i += batchSize) {
                List<FilePart> chunk = toCall.subList(i, Math.min(toCall.size(), i + batchSize));
                List<FapiaoCnClient.FilePart> fparts = chunk.stream()
                        .map(p -> new FapiaoCnClient.FilePart(p.filename, p.bytes))
                        .toList();
                try {
                    results.addAll(fapiaoClient.extractBatch(fparts));
                } catch (com.anomalyco.ifinbrain.web.common.BizException biz) {
                    String msg = biz.getMessage() == null ? "" : biz.getMessage();
                    String tag;
                    if (msg.contains("解密失败")) {
                        tag = "DECRYPTION_FAILED";
                    } else if (biz.getCode() == 503) {
                        tag = "OCR_NOT_CONFIGURED";
                    } else {
                        tag = "OCR_EXTRACT_FAILED";
                    }
                    log.warn("OCR unavailable: tag={} detail={}", tag, msg);
                    chunk.forEach(p -> results.add(ExtractItem.error(p.filename, tag)));
                } catch (Exception ex) {
                    log.error("Batch extract failed", ex);
                    chunk.forEach(p -> results.add(ExtractItem.error(p.filename, ex.getMessage())));
                }
            }
        }

        // 把识别结果 attach 到 pendings（按文件顺序一一对应）
        for (int i = 0; i < pendings.size(); i++) {
            PendingInvoice pi = pendings.get(i);
            ExtractItem r = i < results.size() ? results.get(i) : ExtractItem.error(pi.originalFilename, "no result");
            applyExtract(pi, r);
        }
        return pendings;
    }

    /**
     * 保存：把 pending 列表 + 核销三件套绑定后**重命名 storage 文件 + 落库**。
     * 文件名格式：{发票号码}_{核销单号}.{原扩展名}，落到 storageKey = fapiao/{核销单号}/{新名}。
     * 取消年/月目录归集——按核销单号直接放在 fapiao/ 下。
     */
    @Transactional
    public List<InvoiceRecord> commit(CommitReq req) {
        perm.require("invoice:upload");
        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            throw new BizException(400, "items is required");
        }
        if (req.getVerificationNo() == null || req.getVerificationNo().isBlank()) {
            throw new BizException(400, "核销单号必填");
        }
        LocalDate vDate;
        if (req.getVerificationDate() == null || req.getVerificationDate().isBlank()) {
            vDate = LocalDate.now();
        } else {
            try { vDate = LocalDate.parse(req.getVerificationDate()); }
            catch (Exception e) { throw new BizException(400, "核销日期必须是 YYYY-MM-DD"); }
        }
        String verifier = req.getVerifier();
        String verNo = req.getVerificationNo().trim();

        List<InvoiceRecord> created = new ArrayList<>();
        for (PendingItem pi : req.getItems()) {
            String tempKey = pi.getStorageKey();
            if (tempKey == null) throw new BizException(400, "storageKey missing");

            // 重命名：发票号码_核销单号.{ext}；发票号码为空时退回原文件名
            String invoiceNo = pi.getInvoiceNumber() == null ? "" : pi.getInvoiceNumber().trim();
            String orig = pi.getOriginalFilename() == null ? "file" : pi.getOriginalFilename();
            String ext = extension(orig);
            String baseName;
            if (!invoiceNo.isEmpty()) {
                baseName = safeName(invoiceNo) + "_" + safeName(verNo);
            } else {
                baseName = safeName(stripExt(orig)) + "_" + safeName(verNo);
            }
            String newName = baseName + (ext.isEmpty() ? "" : "." + ext);
            String newKey = INVOICE_KEY_PREFIX + safeName(verNo) + "/" + newName;

            // 复制 temp → 新路径。失败显式抛错（之前是 log.warn + 落库到 tempKey，
            // 导致 DB 行存在但文件不存在 —— 用户看到「前端有条目、后端无文件」）。
            byte[] bytes;
            try {
                bytes = storage.current().readAllBytes(tempKey);
            } catch (Exception ex) {
                log.error("commit: temp file missing/unreadable, tempKey={}: {}", tempKey, ex.getMessage());
                throw new BizException(400,
                        "临时文件已失效（可能因存储切换/清理/超时）。请重新上传。[" + tempKey + "]");
            }
            try {
                storage.current().put(newKey, new ByteArrayInputStream(bytes), bytes.length,
                        "application/octet-stream");
            } catch (Exception ex) {
                log.error("commit: storage put failed, newKey={}: {}", newKey, ex.getMessage(), ex);
                throw new BizException(500,
                        "保存文件到存储失败：" + ex.getMessage() + "。请检查存储配置后重试。");
            }
            // delete(tempKey) 独立 try —— 即使失败也不阻塞提交，orphan _tmp 可下次清理扫掉
            try { storage.current().delete(tempKey); }
            catch (Exception ex) {
                log.warn("commit: temp cleanup failed for {}: {}", tempKey, ex.getMessage());
            }

            InvoiceRecord rec = InvoiceRecord.builder()
                    .storageKey(newKey)
                    .storageType(storage.current().type())
                    .originalFilename(newName)
                    .sizeBytes(pi.getSizeBytes() == null ? 0L : pi.getSizeBytes())
                    .sha256(pi.getSha256())
                    .invoiceType(pi.getInvoiceType())
                    .invoiceNumber(invoiceNo)
                    .issueDate(pi.getIssueDate())
                    .buyerName(pi.getBuyerName())
                    .buyerTaxId(pi.getBuyerTaxId())
                    .sellerName(pi.getSellerName())
                    .sellerTaxId(pi.getSellerTaxId())
                    .amountWithoutTax(pi.getAmountWithoutTax())
                    .taxAmount(pi.getTaxAmount())
                    .totalAmount(pi.getTotalAmount())
                    .totalAmountUpper(pi.getTotalAmountUpper())
                    .payee(pi.getPayee())
                    .reviewer(pi.getReviewer())
                    .drawer(pi.getDrawer())
                    .message(pi.getMessage())
                    .verificationNo(verNo)
                    .verifier(verifier)
                    .verificationDate(vDate)
                    .uploaderId(req.getUploaderId())
                    .build();
            created.add(invoiceRepo.save(rec));
        }
        return created;
    }

    @Transactional
    public void delete(Long id) {
        perm.require("invoice:delete");
        invoiceRepo.findById(id).ifPresent(r -> {
            try {
                storage.current().delete(r.getStorageKey());
            } catch (Exception ex) {
                // 文件删除失败不阻塞 DB 行删除（best-effort 语义，与 dedupe() 一致），
                // 但要留痕 —— 否则 admin 在 S3 异常时看到「删除成功」但文件残留，无法排查。
                log.warn("delete: storage delete failed for id={} key={}: {}",
                        r.getId(), r.getStorageKey(), ex.getMessage());
            }
            invoiceRepo.delete(r);
        });
    }

    /**
     * 批量删除：对应发票列表「一行 = 一组（同发票号 pdf+xml+ofd 多格式）」场景。
     * 单事务：DB 原子删除；存储 best-effort 删除，每条失败都计入 missing 让调用方知情。
     *
     * @return { rowsDeleted, filesDeleted, filesMissing, skipped, missing[] }
     */
    @Transactional
    public Map<String, Object> deleteBatch(List<Long> ids) {
        perm.require("invoice:delete");
        Map<String, Object> result = new LinkedHashMap<>();
        int rowsDeleted = 0, filesDeleted = 0, filesMissing = 0;
        List<Long> skipped = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();

        if (ids == null || ids.isEmpty()) {
            result.put("rowsDeleted", 0);
            result.put("filesDeleted", 0);
            result.put("filesMissing", 0);
            result.put("skipped", List.of());
            result.put("missing", List.of());
            return result;
        }

        for (Long id : ids) {
            if (id == null) { skipped.add(null); continue; }
            var opt = invoiceRepo.findById(id);
            if (opt.isEmpty()) { skipped.add(id); continue; }
            InvoiceRecord r = opt.get();
            String key = r.getStorageKey();
            boolean fileOk = false;
            if (key != null && !key.isBlank()) {
                try {
                    storage.current().delete(key);
                    fileOk = true;
                    filesDeleted++;
                } catch (Exception ex) {
                    filesMissing++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", id);
                    m.put("storageKey", key);
                    m.put("reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    missing.add(m);
                    log.warn("deleteBatch: storage delete failed for id={} key={}: {}",
                            id, key, ex.getMessage());
                }
            } else {
                // 没有 storageKey —— 视为"已无文件"，仍删 DB 行
                filesMissing++;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("storageKey", "");
                m.put("reason", "empty storageKey on record");
                missing.add(m);
            }
            invoiceRepo.delete(r);
            rowsDeleted++;
            log.debug("deleteBatch: removed id={} fileOk={}", id, fileOk);
        }

        result.put("rowsDeleted", rowsDeleted);
        result.put("filesDeleted", filesDeleted);
        result.put("filesMissing", filesMissing);
        result.put("skipped", skipped);
        result.put("missing", missing);
        log.info("deleteBatch: {} rows deleted, {} files removed, {} file failures, {} skipped",
                rowsDeleted, filesDeleted, filesMissing, skipped.size());
        return result;
    }

    private void applyExtract(PendingInvoice pi, ExtractItem r) {
        pi.setInvoiceType(r.getInvoiceType());
        pi.setInvoiceNumber(r.getInvoiceNumber());
        pi.setPageCount(r.getPageCount());
        pi.setIssueDate(r.getIssueDate());
        pi.setBuyerName(r.getBuyerName());
        pi.setBuyerTaxId(r.getBuyerTaxId());
        pi.setSellerName(r.getSellerName());
        pi.setSellerTaxId(r.getSellerTaxId());
        pi.setAmountWithoutTax(r.getAmountWithoutTax());
        pi.setTaxAmount(r.getTaxAmount());
        pi.setTotalAmount(r.getTotalAmount());
        pi.setTotalAmountUpper(r.getTotalAmountUpper());
        pi.setPayee(r.getPayee());
        pi.setReviewer(r.getReviewer());
        pi.setDrawer(r.getDrawer());
        pi.setMessage(r.getMessage() == null ? "unknown" : r.getMessage());
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 解析核销日期期间；任一为空则该端不限。返回 [from, to]，两者均可为 null。 */
    private static LocalDate[] parseDateRange(String fromStr, String toStr) {
        LocalDate from = null;
        LocalDate to = null;
        try {
            if (fromStr != null && !fromStr.isBlank()) from = LocalDate.parse(fromStr);
        } catch (Exception e) { throw new BizException(400, "verificationDateFrom must be YYYY-MM-DD"); }
        try {
            if (toStr != null && !toStr.isBlank()) to = LocalDate.parse(toStr);
        } catch (Exception e) { throw new BizException(400, "verificationDateTo must be YYYY-MM-DD"); }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BizException(400, "verificationDateFrom must be <= verificationDateTo");
        }
        return new LocalDate[] { from, to };
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String safeName(String n) {
        return n.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i <= 0 ? n : n.substring(0, i);
    }

    private static String extension(String n) {
        int i = n.lastIndexOf('.');
        if (i < 0 || i == n.length() - 1) return "";
        String e = n.substring(i + 1).toLowerCase();
        return e.matches("[a-z0-9]{1,5}") ? e : "";
    }

    private record FilePart(String filename, byte[] bytes, String sha, String tempKey) {}

    /** 上传+识别阶段的暂存结果（不落库），用于前端展示与提交。 */
    @lombok.Data
    public static class PendingInvoice {
        private String originalFilename;
        private String sha256;
        private String storageKey;   // 临时 _tmp/...
        private Long sizeBytes;
        private Long uploaderId;
        // 识别字段
        private String invoiceType;
        private String invoiceNumber;
        private Integer pageCount;
        private LocalDate issueDate;
        private String buyerName;
        private String buyerTaxId;
        private String sellerName;
        private String sellerTaxId;
        private java.math.BigDecimal amountWithoutTax;
        private java.math.BigDecimal taxAmount;
        private java.math.BigDecimal totalAmount;
        private String totalAmountUpper;
        private String payee;
        private String reviewer;
        private String drawer;
        private String message;
        public PendingInvoice(String originalFilename, String sha256, String storageKey,
                              long sizeBytes, Long uploaderId) {
            this.originalFilename = originalFilename;
            this.sha256 = sha256;
            this.storageKey = storageKey;
            this.sizeBytes = sizeBytes;
            this.uploaderId = uploaderId;
        }
    }

    /** Commit 请求体：核销三件套 + 待保存的 pending 项（前端从 upload-and-recognize 拿到的）。 */
    @lombok.Data
    public static class CommitReq {
        private List<PendingItem> items;
        private String verificationNo;
        private String verifier;
        private String verificationDate; // YYYY-MM-DD
        private Long uploaderId;
    }

    @lombok.Data
    public static class PendingItem {
        private String originalFilename;
        private String sha256;
        private String storageKey;
        private Long sizeBytes;
        private String invoiceType;
        private String invoiceNumber;
        private LocalDate issueDate;
        private String buyerName;
        private String buyerTaxId;
        private String sellerName;
        private String sellerTaxId;
        private java.math.BigDecimal amountWithoutTax;
        private java.math.BigDecimal taxAmount;
        private java.math.BigDecimal totalAmount;
        private String totalAmountUpper;
        private String payee;
        private String reviewer;
        private String drawer;
        private String message;
    }
}