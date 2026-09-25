package com.anomalyco.ifinbrain.web.documents.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentDto;
import com.anomalyco.ifinbrain.web.documents.dto.DocumentBrowseDto;
import com.anomalyco.ifinbrain.web.documents.dto.FolderDto;
import com.anomalyco.ifinbrain.web.documents.entity.DocumentCategory;
import com.anomalyco.ifinbrain.web.documents.entity.DocumentRecord;
import com.anomalyco.ifinbrain.web.documents.repository.DocumentCategoryRepository;
import com.anomalyco.ifinbrain.web.documents.repository.DocumentRecordRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.storage.StorageSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    /** storage key 顶层前缀。所有档案共享此根。 */
    /** storage key 顶层前缀。所有档案共享此根。 */
    private static final String KEY_PREFIX = "documents/";

    private final DocumentRecordRepository documentRepo;
    private final DocumentCategoryRepository categoryRepo;
    private final StorageSelector storage;
    private final PermissionChecker perm;

    // ===================== 查询 =====================

    @Transactional(readOnly = true)
    public Page<DocumentDto> list(Long categoryId, int page, int size) {
        perm.require("documents:view");
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 20;
        Pageable p = PageRequest.of(page, size);
        return documentRepo.findByCategoryIdAndStatusOrderByCreatedAtDesc(categoryId, (short) 1, p)
                .map(DocumentDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public DocumentDto detail(Long id) {
        perm.require("documents:view");
        return documentRepo.findById(id)
                .map(DocumentDto::fromEntity)
                .orElseThrow(() -> new BizException(404, "档案不存在"));
    }

    // ===================== 上传 =====================

    /**
     * 上传档案：SHA-256 → 去重（同 sha+category 拒绝）→ 写 storage → 落库。
     * 去重命中时抛 409，调用方可提示「该档案已存在」。
     *
     * storage_key 直接用原始文件名（safeName 转义掉危险字符），冲突时自动追加 -1/-2/...。
     * 不再加 sha 前缀 —— 用户在 storage 看到的就是自己上传的"原名"。
     */
    @Transactional
    public DocumentDto upload(Long categoryId, MultipartFile file, String folderPath) {
        perm.require("documents:upload");
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件为空");
        }

        DocumentCategory cat = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new BizException(404, "类目不存在"));
        if (cat.getStatus() == null || cat.getStatus() != 1) {
            throw new BizException(400, "类目已禁用");
        }

        String normalizedFolder = normalizeFolderPath(folderPath);

        String orig = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();

        byte[] data;
        String sha;
        try {
            data = file.getBytes();
            sha = sha256Hex(data);
        } catch (Exception ex) {
            throw new BizException(500, "读取上传文件失败: " + ex.getMessage());
        }

        // 同 sha + 同目录 → 视为重复（同一文件可以放在不同目录）
        String prefix = buildPrefix(cat.getSlug(), normalizedFolder);
        Optional<DocumentRecord> dup = documentRepo
                .findFirstBySha256AndStorageKeyStartingWith(sha, prefix);
        if (dup.isPresent()) {
            throw new BizException(409,
                    "档案已存在（同一目录内已有相同内容的档案）: " + dup.get().getName());
        }
        String key = uniqueKey(prefix, orig);
        storage.current().put(key, new ByteArrayInputStream(data), data.length, mime);

        DocumentRecord rec = DocumentRecord.builder()
                .categoryId(categoryId)
                .name(orig)
                .storageKey(key)
                .storageType(storage.current().type())
                .originalFilename(orig)
                .sizeBytes((long) data.length)
                .sha256(sha)
                .mimeType(mime)
                .uploaderId(perm.currentUserId())
                .uploaderName(perm.currentUsername())
                .status((short) 1)
                .build();
        rec = documentRepo.save(rec);

        log.info("[documents] uploaded id={} cat={} path={} key={} size={} sha={}",
                rec.getId(), categoryId, normalizedFolder, key, data.length, sha.substring(0, 8));
        return DocumentDto.fromEntity(rec);
    }

    /**
     * 生成唯一 storage key：先试原始文件名（safeName 后），存在则追加 -1/-2/...。
     * 不会重命名用户上传的文件名 —— 只是 storage 层的 disambiguation。
     * 原始 `name` 仍存 DB（DocumentRecord.name），用户在表格里看到的还是原名。
     */
    private String uniqueKey(String prefix, String originalFilename) {
        String safe = safeName(originalFilename);
        String candidate = prefix + safe;
        if (!storage.current().exists(candidate)) return candidate;

        // 拆 base / ext
        int dot = safe.lastIndexOf('.');
        String base, ext;
        if (dot > 0 && dot < safe.length() - 1) {
            base = safe.substring(0, dot);
            ext = safe.substring(dot); // 含点
        } else {
            base = safe;
            ext = "";
        }
        for (int i = 1; i < 1000; i++) {
            String c = prefix + base + "-" + i + ext;
            if (!storage.current().exists(c)) return c;
        }
        throw new BizException(500, "无法为「" + originalFilename + "」生成唯一文件名（已尝试 999 次）");
    }

    // ===================== 浏览（文件夹 + 文件） =====================

    /**
     * 浏览类目某路径下的内容：返回 immediate 子文件夹 + immediate 文件。
     * - 子文件夹从 storage.listFolders(prefix) 来（真目录）
     * - 文件从 sys_document 查（storage_key 深度匹配 prefix 深度 + 1）
     */
    @Transactional(readOnly = true)
    public DocumentBrowseDto browse(Long categoryId, String folderPath) {
        perm.require("documents:view");
        DocumentCategory cat = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new BizException(404, "类目不存在"));
        String normalizedFolder = normalizeFolderPath(folderPath);
        String prefix = buildPrefix(cat.getSlug(), normalizedFolder);

        // 子文件夹
        List<String> folderNames = storage.current().listFolders(prefix);
        List<FolderDto> folders = folderNames.stream()
                .map(name -> FolderDto.builder()
                        .name(name)
                        .path(joinPath(normalizedFolder, name))
                        .build())
                .toList();

        // 文件：sys_document.storage_key LIKE prefix%
        List<DocumentRecord> all = documentRepo.findByCategoryIdAndStorageKeyStartingWith(
                categoryId, prefix);
        List<DocumentDto> files = all.stream()
                // 只保留 prefix 之后的 immediate 文件名（不含中间 /）
                // —— 避免把子目录里的文件也带出来
                // （flat 布局下 slashCount 区分不开 "documents/cw/file" 与 "documents/cw/demo/file"，
                //  必须看 prefix 之后是否有子目录斜杠）
                .filter(r -> !r.getStorageKey().substring(prefix.length()).contains("/"))
                .map(DocumentDto::fromEntity)
                .toList();

        return DocumentBrowseDto.builder()
                .folders(folders)
                .files(files)
                .currentPath(normalizedFolder)
                .categorySlug(cat.getSlug())
                .build();
    }

    // ===================== 重命名 =====================

    // ===================== 重命名 =====================

    @Transactional
    public DocumentDto rename(Long id, String newName) {
        perm.require("documents:update");
        DocumentRecord rec = documentRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "档案不存在"));
        String trimmed = newName.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(400, "name 不能为空");
        }
        rec.setName(trimmed);
        rec = documentRepo.save(rec);
        return DocumentDto.fromEntity(rec);
    }

    // ===================== 删除 =====================

    @Transactional
    public void delete(Long id) {
        perm.require("documents:delete");
        DocumentRecord rec = documentRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "档案不存在"));

        // 先删 storage（失败不回滚 DB；记 warn 让用户感知）
        try {
            storage.current().delete(rec.getStorageKey());
        } catch (Exception ex) {
            log.warn("[documents] storage delete failed for key={}: {}", rec.getStorageKey(), ex.getMessage());
        }
        documentRepo.delete(rec);
        log.info("[documents] deleted id={} key={}", id, rec.getStorageKey());
    }

    // ===================== 文件夹 =====================

    /**
     * 在类目下创建子文件夹路径。空目录也算合法（即使类目下 0 个文件，目录结构也会存在）。
     *
     * - local：mkdir -p documents/{slug}/{path}/ （含中间路径）
     * - s3   ：no-op（path 在首次 put 时隐式创建）
     *
     * 注：当前实现只创建空目录，不影响现有文件上传路径（files 仍然落到 documents/{slug}/yyyy/MM/...）。
     * 文件夹作为"组织标记"用途：例如先建好 2024-q1/原始扫描件 目录，后续手动/外部工具往里放文件。
     */
    @Transactional
    public String createFolder(Long categoryId, String folderPath) {
        perm.require("documents:upload");
        DocumentCategory cat = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new BizException(404, "类目不存在"));
        if (cat.getStatus() == null || cat.getStatus() != 1) {
            throw new BizException(400, "类目已禁用");
        }

        // 清理：去掉首尾 / 与多余空白
        String cleanPath = folderPath == null ? "" : folderPath.trim().replaceAll("^/+|/+$", "");
        if (cleanPath.isEmpty()) {
            throw new BizException(400, "文件夹路径不能为空");
        }
        // 校验每段：Unicode 字符 + 数字 + 连字符 + 空格（ASCII/中文都允许）
        // 禁止：路径穿越（..）与危险字符（\\ : * ? " < > | 等 Windows 保留字符）
        for (String seg : cleanPath.split("/")) {
            if (seg.isEmpty()) {
                throw new BizException(400, "路径段不能为空（连续 / 不允许）");
            }
            if (seg.equals(".") || seg.equals("..")) {
                throw new BizException(400, "路径段不能为 . 或 ..");
            }
            if (!seg.matches("^[\\p{L}\\p{N}\\-\\s_]+$")) {
                throw new BizException(400,
                        "文件夹路径每段只能包含字母/数字/连字符/下划线/空格（支持中文），单段或多段用 / 分隔");
            }
        }

        String fullPath = "documents/" + cat.getSlug() + "/" + cleanPath + "/";
        storage.current().ensurePrefix(fullPath);
        log.info("[documents] created folder category={} slug={} path={}", cat.getId(), cat.getSlug(), fullPath);
        return fullPath;
    }

    // ===================== helpers =====================

    /** 把路径规范化：去首尾 /、压缩连续 // —— 顶层容错 */
    private static String normalizeFolderPath(String path) {
        if (path == null) return "";
        String trimmed = path.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) return "";
        // 去掉首尾 /
        String stripped = trimmed.replaceAll("^/+|/+$", "");
        // 压缩连续 // → /
        return stripped.replaceAll("/+", "/");
    }

    /** 拼接父子路径 —— 根 + 子段，或 子/孙 */
    private static String joinPath(String parent, String child) {
        if (parent == null || parent.isEmpty()) return child;
        return parent + "/" + child;
    }

    /** 拼 prefix：documents/{slug}/{folder?}/ */
    private static String buildPrefix(String slug, String folder) {
        StringBuilder sb = new StringBuilder(KEY_PREFIX).append(slug).append("/");
        if (folder != null && !folder.isEmpty()) sb.append(folder).append("/");
        return sb.toString();
    }

    /** 数路径中 / 的个数（用于深度匹配） */
    private static int slashCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '/') n++;
        return n;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static final java.util.regex.Pattern SAFE_NAME =
            java.util.regex.Pattern.compile("[^\\p{L}\\p{N}._\\-\\s]",
                    java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    /** 把文件名里"路径危险 / 平台不兼容"字符替换成下划线。
     * 保留：Unicode 字母 + 数字 + . _ - 空格（中文 / 空格文件名都允许） */
    private static String safeName(String n) {
        return SAFE_NAME.matcher(n).replaceAll("_");
    }
}
