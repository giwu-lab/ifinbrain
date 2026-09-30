package com.anomalyco.ifinbrain.web.controller;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.documents.repository.DocumentRecordRepository;
import com.anomalyco.ifinbrain.web.entity.SysSetting;
import com.anomalyco.ifinbrain.web.repository.SysSettingRepository;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.storage.LocalStorageService;
import com.anomalyco.ifinbrain.web.storage.S3StorageService;
import com.anomalyco.ifinbrain.web.storage.StorageSelector;
import com.anomalyco.ifinbrain.web.storage.StorageService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final StorageSelector storage;
    private final LocalStorageService localStorage;
    private final S3StorageService s3Storage;
    private final DocumentRecordRepository documentRepo;
    private final SysSettingRepository settingRepo;
    private final PermissionChecker perm;

    /** 档案库 storage key 格式：documents/{slug}/{rest}。
     *  拿到 slug 才能校验 per-archive view 权限。
     *  非档案库文件（如 invoice 的 OFD/PDF）key 不匹配，返回 null，由调用方按需 bypass。 */
    private static final Pattern DOC_KEY = Pattern.compile("^documents/([^/]+)(?:/.*)?$");

    /** 按文件后缀返回正确的 Content-Type，保证浏览器能内嵌预览 / 下载保留扩展名 */
    private static String contentTypeFor(String filename) {
        if (filename == null) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))  return MediaType.APPLICATION_PDF_VALUE;
        if (lower.endsWith(".xml"))  return MediaType.APPLICATION_XML_VALUE;
        if (lower.endsWith(".ofd"))  return "application/ofd";
        if (lower.endsWith(".png"))  return MediaType.IMAGE_PNG_VALUE;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG_VALUE;
        if (lower.endsWith(".gif"))  return MediaType.IMAGE_GIF_VALUE;
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        if (lower.endsWith(".bmp"))  return "image/bmp";
        if (lower.endsWith(".txt"))  return "text/plain; charset=utf-8";
        if (lower.endsWith(".csv"))  return "text/csv; charset=utf-8";
        if (lower.endsWith(".md"))   return "text/markdown; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml"))
                                 return "application/x-yaml; charset=utf-8";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".mp4"))  return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp3"))  return "audio/mpeg";
        if (lower.endsWith(".wav"))  return "audio/wav";
        if (lower.endsWith(".ogg"))  return "audio/ogg";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    @GetMapping("/raw")
    public ResponseEntity<?> raw(@RequestParam String key,
                                 @RequestParam(value = "download", required = false) boolean download,
                                 HttpServletResponse response) {
        // key 来源是 query string（用户可控） —— 用 java.nio.Path 校验，不只防 ".."
        // 1) reject null/blank
        // 2) reject 绝对路径（以 "/" 开头）
        // 3) reject ".." 任何出现位置（防 path traversal）
        // 4) reject 含反斜杠（防 windows-style 路径绕过）
        // 5) reject NUL / 控制字符（防 log injection & 部分 driver 拒绝）
        if (key == null || key.isBlank()) {
            throw new BizException(400, "Invalid key");
        }
        if (key.startsWith("/") || key.startsWith("\\")) {
            throw new BizException(400, "Invalid key");
        }
        if (key.contains("..") || key.contains("\\") || key.indexOf('\0') >= 0) {
            throw new BizException(400, "Invalid key");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x20 && c != '\t') {
                throw new BizException(400, "Invalid key");
            }
        }

        // 档案库文件 → 校验 per-archive view 权限（无需全局 file:download —— 档案库 view 已是充分条件）
        Matcher m = DOC_KEY.matcher(key);
        if (m.matches()) {
            String slug = m.group(1);
            String archivePerm = "documents:" + slug + ":view";
            // 档案库 view 权限 = 能看档案库内文件列表；preview / download 顺理成章应受同一权限保护
            if (!perm.has(archivePerm)) {
                throw new BizException(403, "无法下载 - 该档案库未授权「查看」权限");
            }
        } else {
            // 非档案库文件（如 invoice 的 OFD/PDF）→ 需要全局 file:download 权限
            perm.require("file:download");
        }

        String fname = key.substring(key.lastIndexOf('/') + 1);

        // L1+L3：read 路径按 storage_type 路由（hotfix），并支持 auto-fallback（L3 仅 admin 显式开启）。
        StorageService backend = resolveBackend(key);

        // 直接下载（?download=true）：永远 backend 流式代理。
        // 之前 S3 走 302 → presigned URL，但前端下载走 fetch().blob() 会跟随重定向到 S3，
        // 而 S3/MinIO 默认没配 CORS header → 浏览器拦截 "No 'Access-Control-Allow-Origin' header"。
        // 改为 backend 全程代理：避开 CORS 依赖，与 local 行为一致。代价是 backend 多一跳带宽。
        // 预签名 URL 仍由 S3StorageService.getPresignedUrl() 暴露给 SettingController.testStorage 用。
        try (InputStream in = backend.get(key)) {
            response.setContentType(contentTypeFor(fname));
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    (download ? "attachment; " : "inline; ") +
                    "filename*=UTF-8''" + URLEncoder.encode(fname, StandardCharsets.UTF_8));
            in.transferTo(response.getOutputStream());
            return null;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * L1：按文档级 storage_type 路由到正确后端，避免切全局 storage.type 后老文件 404。
     * L3：当 storage.fallback.enabled='true' 且首选后端缺文件时，回退到另一后端（仅迁移期使用）。
     *
     * 边界：
     * - 非 documents/{slug}/* key（invoice 等）：fallback 到当前全局后端
     * - DB 无此 row（脏数据 / PENDING_UPLOAD 期间前端提前拿 key）：fallback 到当前全局后端
     * - 未知的 storage_type 值（如未来 'azure' 未实现）：fallback 到当前全局后端
     */
    private StorageService resolveBackend(String key) {
        if (!DOC_KEY.matcher(key).matches()) {
            return storage.current();
        }
        String t = documentRepo.findStorageTypeByKey(key).orElse(storage.currentType());
        StorageService primary = pickByType(t);

        boolean fallbackEnabled = "true".equalsIgnoreCase(
                settingRepo.findById("storage.fallback.enabled")
                        .map(SysSetting::getValueText)
                        .orElse("false"));
        if (fallbackEnabled && !primary.exists(key)) {
            String altType = "local".equals(t) ? "s3" : "local";
            StorageService alt = pickByType(altType);
            if (alt != null && alt != primary && alt.exists(key)) {
                log.warn("[storage] primary {} miss for key={}, fallback to {}", primary.type(), key, alt.type());
                return alt;
            }
        }
        return primary;
    }

    private StorageService pickByType(String t) {
        if (t == null) return storage.current();
        return switch (t) {
            case "local" -> localStorage;
            case "s3"    -> s3Storage;
            default      -> storage.current();
        };
    }
}