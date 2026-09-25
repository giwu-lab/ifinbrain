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
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final StorageSelector storage;
    private final PermissionChecker perm;

    /** 按文件后缀返回正确的 Content-Type，保证浏览器能内嵌预览 / 下载保留扩展名 */
    private static String contentTypeFor(String filename) {
        if (filename == null) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF_VALUE;
        if (lower.endsWith(".xml")) return MediaType.APPLICATION_XML_VALUE;
        if (lower.endsWith(".ofd")) return "application/ofd";
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG_VALUE;
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    @GetMapping("/raw")
    public ResponseEntity<?> raw(@RequestParam String key,
                                 @RequestParam(value = "download", required = false) boolean download,
                                 HttpServletResponse response) {
        perm.require("file:download");
        if (key == null || key.isBlank() || key.contains("..")) {
            throw new BizException(400, "Invalid key");
        }
        String fname = key.substring(key.lastIndexOf('/') + 1);

        // 直接下载（OFD / 显式 ?download=true）：S3 → 302 presigned（浏览器 <a download> 不受 CORS 限制）；
        // local 时 fallthrough 到下面的 backend 流式分支。
        if (download) {
            String presigned = storage.presignedOrNull(key);
            if (presigned != null) {
                return ResponseEntity.status(302).header(HttpHeaders.LOCATION, presigned).build();
            }
        }

        // 内嵌预览（PDF/XML 默认 download=false）：永远 backend 流式，避开 RustFS CORS。
        // local + download=true 也会走这条路径。
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