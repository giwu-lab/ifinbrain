package com.anomalyco.ifinbrain.web.controller;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.storage.StorageSelector;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final StorageSelector storage;
    private final PermissionChecker perm;

    /** 文档类目 storage key 格式：documents/{slug}/{rest}。
     *  拿到 slug 才能校验 per-category view 权限。
     *  非文档类目文件（如 invoice 的 OFD/PDF）key 不匹配，返回 null，由调用方按需 bypass。 */
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

        // 文档类目文件 → 校验 per-category view 权限（无需全局 file:download —— 类目 view 已是充分条件）
        Matcher m = DOC_KEY.matcher(key);
        if (m.matches()) {
            String slug = m.group(1);
            String categoryPerm = "documents:" + slug + ":view";
            // 类目 view 权限 = 能看类目内文件列表；preview / download 顺理成章应受同一权限保护
            if (!perm.has(categoryPerm)) {
                throw new BizException(403, "无法下载 - 该类目未授权「查看」权限");
            }
        } else {
            // 非文档类目文件（如 invoice 的 OFD/PDF）→ 需要全局 file:download 权限
            perm.require("file:download");
        }

        String fname = key.substring(key.lastIndexOf('/') + 1);

        // 直接下载（?download=true）：永远 backend 流式代理。
        // 之前 S3 走 302 → presigned URL，但前端下载走 fetch().blob() 会跟随重定向到 S3，
        // 而 S3/MinIO 默认没配 CORS header → 浏览器拦截 "No 'Access-Control-Allow-Origin' header"。
        // 改为 backend 全程代理：避开 CORS 依赖，与 local 行为一致。代价是 backend 多一跳带宽。
        // 预签名 URL 仍由 S3StorageService.getPresignedUrl() 暴露给 SettingController.testStorage 用。
        try (InputStream in = storage.current().get(key)) {
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
}