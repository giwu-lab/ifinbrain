package com.anomalyco.ifinbrain.web.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> biz(BizException ex) {
        log.warn("BizException: {}", ex.getMessage());
        HttpStatus status;
        switch (ex.getCode()) {
            case 401 -> status = HttpStatus.UNAUTHORIZED;
            case 403 -> status = HttpStatus.FORBIDDEN;
            case 404 -> status = HttpStatus.NOT_FOUND;
            case 409 -> status = HttpStatus.CONFLICT;
            case 413 -> status = HttpStatus.PAYLOAD_TOO_LARGE;
            case 422 -> status = HttpStatus.UNPROCESSABLE_ENTITY;
            default  -> status = ex.getCode() >= 400 && ex.getCode() < 600
                    ? HttpStatus.valueOf(ex.getCode())
                    : HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    /**
     * S3 异常：把 SDK 错误翻成可操作的提示（accessKey 失效 / bucket 不存在等）。
     */
    @ExceptionHandler(S3Exception.class)
    public ResponseEntity<Result<Void>> s3(S3Exception ex) {
        log.error("S3 error: statusCode={}, awsCode={}, message={}",
                ex.statusCode(),
                ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorCode() : "?",
                ex.getMessage());
        int sc = ex.statusCode();
        String code = ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorCode() : "";
        String msg;
        if (sc == 403 || "InvalidAccessKeyId".equals(code) || "SignatureDoesNotMatch".equals(code) || "AccessDenied".equals(code)) {
            msg = "S3 访问被拒绝（accessKey / secretKey 无效或权限不足）。请到「系统设置 → 存储」检查 S3 配置，或临时切换为 local 存储。";
        } else if (sc == 404 || "NoSuchBucket".equals(code)) {
            msg = "S3 bucket 不存在或区域不匹配。请到「系统设置 → 存储」核对 endpoint / bucket / region。";
        } else {
            msg = "S3 调用失败 [" + sc + " " + code + "]：" + ex.getMessage();
        }
        return ResponseEntity.ok(Result.fail(503, msg));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.ok(Result.fail(400, msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> constraint(ConstraintViolationException ex) {
        return ResponseEntity.ok(Result.fail(400, ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> upload(MaxUploadSizeExceededException ex) {
        return ResponseEntity.ok(Result.fail(413, "上传文件超过大小限制"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(403, "权限不足"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> auth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(401, "未登录或登录已过期"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unknown(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.ok(Result.fail(500, "服务器内部错误: " + ex.getMessage()));
    }
}