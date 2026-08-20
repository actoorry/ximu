package com.by.ximu.common.web.config;

import com.by.ximu.common.ForbiddenException;
import com.by.ximu.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * 全局异常处理：统一转 {@link Result}，并携带真实 HTTP 状态码（P2-1：原实现恒 200，
 * WAF/监控/网关/前端拦截器全部按 2xx 处理，错误被吞）。
 *
 * <p>状态码映射：403 越权 / 400 参数与校验 / 409 状态机与并发冲突 / 500 兜底。
 * body 内 {@code code} 语义保持不变（前端已有依赖：403/400/500 同值，业务冲突仍为 1）。
 *
 * <p>消息泄露面控制（P2-2）：业务异常（IAE/ISE）消息全部来自业务 throw 点的文案，原样返回；
 * 框架层异常（JSON 转换、数据完整性冲突）的 message 含 Java 类名/约束名/SQL 状态码，映射固定文案，明细只进日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 越权/职责分离冲突 → HTTP 403 */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Result<Void>> handleForbidden(ForbiddenException e) {
        log.warn("越权操作: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.error(403, e.getMessage()));
    }

    /** 状态机非法迁移/乐观锁与条件删除并发冲突 → HTTP 409（body code=1 业务失败，保持既有语义） */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException e) {
        log.warn("状态机非法操作: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.error(1, e.getMessage()));
    }

    /** 非法参数（消息为业务 throw 点文案）→ HTTP 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArg(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.error(400, e.getMessage()));
    }

    /** JSON 请求体解析/转换失败（Jackson 消息含类名与字段路径，属内部信息）→ HTTP 400 固定文案 */
    @ExceptionHandler(HttpMessageConversionException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageConversionException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(400, "请求体格式错误，请检查 JSON 结构与字段类型"));
    }

    /** 数据完整性冲突（唯一键/外键/CHECK，消息含约束名与 SQL 状态码）→ HTTP 400 固定文案 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("数据完整性冲突: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(400, "数据完整性冲突（唯一键或约束校验失败），请检查提交内容是否重复或越界"));
    }

    /** @RequestBody Bean 校验异常 → HTTP 400（字段级提示，无内部信息） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.error(400, msg));
    }

    /** query string 参数绑定异常 → HTTP 400 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.error(400, msg));
    }

    /** 资源不存在（各 GET /{id} 未命中）→ HTTP 404（R2-P2-16/9：统一 Result 形态，替代 code=0 data=null） */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoSuchElementException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, e.getMessage()));
    }

    /** 未映射路径 → HTTP 404（R2-P2-30：需 yml 开 throw-exception-if-no-handler-found，统一 Result 形态） */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandler(NoHandlerFoundException e) {
        log.warn("路径未映射: {}", e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(404, "接口不存在: " + e.getRequestURL()));
    }

    /** 兜底异常：detail 只进日志，不返前端，避免泄露内部信息 → HTTP 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "服务器内部错误，请联系管理员"));
    }
}
