package com.kb.kbplatform.web;

import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：把业务异常（如账号禁用、密码错误、用户名重复等）
 * 统一返回中文提示，避免前端收到一脸懵的 500。
 * 对 ResponseStatusException（控制器抛出的 401/403/404 等）保留其真实状态码，
 * 避免被一律改成 400 而掩盖鉴权/越权信号。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 客户端主动断开连接（刷新/跳转/取消下载大文件等）：连接已断，无法也不应写响应。
     *  必须静默忽略——若走通用兜底会打印 error 堆栈，且试图写 JSON 时因响应头已为
     *  audio/image 等静态资源类型而二次抛「No converter」异常。 */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException e) {
        // 浏览器取消导致，Tomcat 已处理该连接，无需记录
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getReason() == null ? "操作失败" : e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage() == null ? "操作失败" : e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 未预期异常兜底：服务端记完整堆栈，客户端只给通用文案，避免泄露 SQL/内部实现细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("未处理异常：", e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "服务器内部错误，请稍后重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
