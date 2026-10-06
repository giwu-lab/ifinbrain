package com.anomalyco.ifinbrain.web.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
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
        // 注意：必须先看 awsCode 再看 sc —— 因为 NoSuchKey / NoSuchBucket 都是 404，
        // 但含义完全不同（文件丢失 vs bucket 不存在），不能让 status code 短路掉 code。
        if ("InvalidAccessKeyId".equals(code) || "SignatureDoesNotMatch".equals(code)
                || "AccessDenied".equals(code) || sc == 403) {
            msg = "S3 访问被拒绝（accessKey / secretKey 无效或权限不足）。请到「系统设置 → 存储」检查 S3 配置，或临时切换为 local 存储。";
        } else if ("NoSuchBucket".equals(code)) {
            msg = "S3 bucket 不存在或区域不匹配。请到「系统设置 → 存储」核对 endpoint / bucket / region。";
        } else if ("NoSuchKey".equals(code)) {
            msg = "S3 文件不存在。可能因存储切换 / 清理 / 手动删除而丢失。";
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

    // ===================== 请求绑定 / 路由层的客户端错误 =====================
    // 这几个以前都没有专属分支，会掉进下面的兜底 handler 变成
    // 「HTTP 200 + {code:500, message:"服务器内部错误"}」——
    // 明明是调用方传错了参数，却被报成服务端故障，日志里还会打一整条 Unhandled exception 栈。
    // 这里统一归位成 4xx。

    /**
     * 路径变量 / 查询参数类型转换失败。
     * 例：GET /api/bi/dashboard/abc —— id 声明为 Long，收到的却是 "abc"。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> typeMismatch(MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        String msg = "参数 " + ex.getName() + " 格式不正确"
                + (required == null ? "" : "（需要 " + required.getSimpleName() + " 类型）")
                + "，实际收到: " + ex.getValue();
        return ResponseEntity.badRequest().body(Result.fail(400, msg));
    }

    /** 缺少必填的查询参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> missingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(Result.fail(400, "缺少必填参数: " + ex.getParameterName()));
    }

    /** 请求体解析失败（JSON 语法错 / 类型对不上） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> unreadable(HttpMessageNotReadableException ex) {
        // 只进日志不回前端：Jackson 的 message 含字段路径和原始片段片段，原样透出对用户无意义
        log.warn("HttpMessageNotReadable: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(400, "请求体格式不正确"));
    }

    /** 请求方法不支持（如给只读接口发 POST） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> methodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(405, "不支持的请求方法: " + ex.getMethod()));
    }

    /**
     * 路径没匹配到任何 handler（打错 URL）。
     * Spring 6 默认把它交给静态资源处理器，最终抛 NoResourceFoundException；
     * 没有这个分支时它会掉进兜底 handler → code 500 + 整条 ERROR 栈，
     * 把「地址写错了」报成「服务端炸了」。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> notFound(Exception ex) {
        String path = ex instanceof NoResourceFoundException nrf ? nrf.getResourcePath() : null;
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(404, "接口不存在" + (path == null ? "" : ": /" + path)));
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
        // 注意：只回固定文案给客户端，不回 ex.getMessage() —— 避免泄漏 SQL/路径/堆栈片段给前端。
        // 详细异常已写入服务端 log（见上）。
        return ResponseEntity.ok(Result.fail(500, "服务器内部错误"));
    }
}