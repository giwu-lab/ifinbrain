package com.anomalyco.ifinbrain.web.todo.service;

import com.anomalyco.ifinbrain.web.common.BizException;
import com.anomalyco.ifinbrain.web.security.PermissionChecker;
import com.anomalyco.ifinbrain.web.storage.StorageSelector;
import com.anomalyco.ifinbrain.web.todo.dto.TodoAttachmentDto;
import com.anomalyco.ifinbrain.web.todo.entity.TodoAttachment;
import com.anomalyco.ifinbrain.web.todo.entity.TodoTask;
import com.anomalyco.ifinbrain.web.todo.repository.TodoAttachmentRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 任务清单附件服务。设计要点：
 * - 单任务内同 SHA 去重（不像 documents 那样做 per-prefix 去重 —— 同一任务允许不同文件名但 SHA 不允许）。
 * - 路径：todos/{taskId}/{safeFilename}；taskId 稳定 → assignee 改动不影响 storage key。
 * - 大小硬限 20MB（前端 beforeUpload + 后端 service 校验两道防御）。
 * - 删除：先删 storage（失败仅 warn）+ soft-delete DB row（status=0），不真删以留审计痕迹。
 * - 跨用户鉴权：loadForAttachment(taskId) 校验 assignee = me 或 view-all perm。
 */
@Service @RequiredArgsConstructor @Slf4j
public class TodoAttachmentService {

    /** 附件大小硬限制：20MB。 */
    public static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;

    private static final String KEY_PREFIX = "todos/";

    /** 文件名安全字符：字母 / 数字 / . _ - 空格 / 中文。其它 → 下划线。 */
    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5 ]");

    private final TodoAttachmentRepository attachmentRepo;
    private final TodoTaskService taskService;
    private final StorageSelector storage;
    private final PermissionChecker perm;

    // ---- 上传 ----

    @Transactional
    public TodoAttachmentDto upload(Long taskId, MultipartFile file) {
        perm.require("todo:attachment:upload");
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件为空");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BizException(413, "文件超过 20MB 限制");
        }

        // 校验任务**可写**：跨部门任务在此 403。上传附件同样是写操作，
        // 否则「跨部门只读」就留了个往别人任务里塞文件的口子。
        taskService.loadForAttachmentWrite(taskId);
        Long uploaderId = perm.currentUserId();
        String uploaderName = perm.currentUsername();

        String orig = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();

        // 流式算 SHA-256 + 写临时文件
        java.nio.file.Path tmp = null;
        String sha;
        long size;
        try {
            tmp = Files.createTempFile("todo-att-", ".tmp");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream raw = file.getInputStream();
                 DigestInputStream dis = new DigestInputStream(raw, md);
                 java.io.OutputStream out = Files.newOutputStream(tmp)) {
                dis.transferTo(out);
            }
            sha = HexFormat.of().formatHex(md.digest());
            size = Files.size(tmp);
        } catch (Exception ex) {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            throw new BizException(500, "读取上传文件失败");
        }

        // 同任务内 SHA 去重
        Optional<TodoAttachment> dup = attachmentRepo.findByTaskIdAndStatusOrderByCreatedAtDesc(taskId, (short) 1).stream()
                .filter(a -> sha.equals(a.getSha256()))
                .findFirst();
        if (dup.isPresent()) {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            throw new BizException(409, "该任务下已存在相同内容的附件: " + dup.get().getOriginalFilename());
        }

        String prefix = KEY_PREFIX + taskId + "/";
        String key = uniqueKey(prefix, orig);
        try (InputStream in = Files.newInputStream(tmp)) {
            storage.current().put(key, in, size, mime);
        } catch (Exception ex) {
            throw new BizException(500, "保存到存储失败");
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }

        TodoAttachment rec = TodoAttachment.builder()
                .taskId(taskId)
                .storageKey(key)
                .storageType(storage.current().type())
                .originalFilename(orig)
                .sizeBytes(size)
                .sha256(sha)
                .mimeType(mime)
                .uploaderId(uploaderId)
                .uploaderName(uploaderName)
                .status((short) 1)
                .build();
        rec = attachmentRepo.save(rec);

        log.info("[todo-att] uploaded id={} task={} uploader={} key={} size={} sha={}",
                rec.getId(), taskId, uploaderId, key, size, sha.substring(0, 8));
        return TodoAttachmentDto.fromEntity(rec);
    }

    // ---- 列表 ----

    @Transactional(readOnly = true)
    public List<TodoAttachmentDto> list(Long taskId) {
        perm.require("todo:attachment:download");
        // 校验可见性（loadForAttachmentRead 已 require("todo:task:view")）
        taskService.loadForAttachmentRead(taskId);
        return attachmentRepo.findByTaskIdAndStatusOrderByCreatedAtDesc(taskId, (short) 1).stream()
                .map(TodoAttachmentDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Long> idsForTask(Long taskId) {
        return attachmentRepo.findByTaskIdAndStatusOrderByCreatedAtDesc(taskId, (short) 1).stream()
                .map(TodoAttachment::getId)
                .toList();
    }

    // ---- 删除 ----

    @Transactional
    public void delete(Long attachmentId) {
        perm.require("todo:attachment:delete");
        TodoAttachment a = attachmentRepo.findByIdAndStatus(attachmentId, (short) 1)
                .orElseThrow(() -> new BizException(404, "附件不存在"));
        // 跨部门鉴权：删除权限**跟随任务的可写性**，不再看 view-all ——
        // 那是只读权限，旧的 isAssigneeOrAdmin 判断等于给 view-all 开了删除后门。
        // 上传者仍可删自己传的那一份：任务转手后那文件还是他的，这是对自己文件的控制权，
        // 与「能否管理这个任务」是两件事。
        TodoTask task = taskService.loadForAttachmentRead(a.getTaskId());
        Long me = perm.currentUserId();
        boolean isUploader = a.getUploaderId() != null && a.getUploaderId().equals(me);
        if (!taskService.canWrite(task) && !isUploader) {
            throw new BizException(403, "无权删除该附件：跨部门任务仅可查看");
        }
        // 先删 storage（失败 warn 但不阻塞）
        try {
            storage.current().delete(a.getStorageKey());
        } catch (Exception ex) {
            log.warn("[todo-att] storage delete failed for key={}: {}", a.getStorageKey(), ex.getMessage());
        }
        a.setStatus((short) 0);
        attachmentRepo.save(a);
        log.info("[todo-att] deleted id={} task={} by user={}", attachmentId, a.getTaskId(), me);
    }

    // ---- 下载 ----

    /**
     * 流式输出附件内容到 response。perm 校验：任务的 assignee 或 view-all perm。
     *  - inline=true：浏览器尝试内嵌（image/pdf 等）
     *  - inline=false：强制下载
     */
    public void streamRaw(Long attachmentId, boolean download, HttpServletResponse response) {
        perm.require("todo:attachment:download");
        TodoAttachment a = attachmentRepo.findByIdAndStatus(attachmentId, (short) 1)
                .orElseThrow(() -> new BizException(404, "附件不存在"));
        taskService.loadForAttachmentRead(a.getTaskId()); // 校验任务可见性
        String fname = a.getOriginalFilename() != null ? a.getOriginalFilename() : "file";
        try (InputStream in = storage.current().get(a.getStorageKey())) {
            response.setContentType(a.getMimeType() != null ? a.getMimeType() : "application/octet-stream");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    (download ? "attachment; " : "inline; ") +
                    "filename*=UTF-8''" + URLEncoder.encode(fname, StandardCharsets.UTF_8));
            in.transferTo(response.getOutputStream());
        } catch (IOException ex) {
            log.warn("[todo-att] stream failed id={} key={}: {}", attachmentId, a.getStorageKey(), ex.getMessage());
            throw new BizException(500, "读取附件失败");
        }
    }

    // ---- 私有工具 ----

    /** 生成唯一 storage key：原始文件名（safeName 后），冲突追加 -1/-2/...。 */
    private String uniqueKey(String prefix, String originalFilename) {
        String safe = safeName(originalFilename);
        String candidate = prefix + safe;
        if (!storage.current().exists(candidate)) return candidate;

        int dot = safe.lastIndexOf('.');
        String base, ext;
        if (dot > 0 && dot < safe.length() - 1) {
            base = safe.substring(0, dot);
            ext = safe.substring(dot);
        } else {
            base = safe;
            ext = "";
        }
        for (int i = 1; i < 1000; i++) {
            String c = prefix + base + "-" + i + ext;
            if (!storage.current().exists(c)) return c;
        }
        throw new BizException(500, "无法为「" + originalFilename + "」生成唯一文件名");
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) return "file";
        String cleaned = SAFE_NAME.matcher(name).replaceAll("_");
        // 防止路径穿越
        cleaned = cleaned.replace("..", "_").replace("/", "_").replace("\\", "_");
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        return cleaned;
    }
}