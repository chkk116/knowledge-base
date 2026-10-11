/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

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

    /** 业务性失败（评论内容为空/敏感词等用户输入不合规）：不算 HTTP 错误，
     *  统一 200 + { ok:false, error }，前端按业务提示处理，避免在控制台留下 4xx 红字。 */
    @ExceptionHandler(com.kb.kbplatform.service.BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(com.kb.kbplatform.service.BizException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", e.getMessage() == null ? "操作失败" : e.getMessage());
        return ResponseEntity.ok(body);
    }

    /** 参数类型不匹配（如 /api/docs/abc 里的 id）：Spring 默认会把内部转换异常全文回显，
     *  泄露参数名与类型。统一收敛为泛化文案，详情只在服务端日志。 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        log.warn("参数格式错误：{} = {}", e.getName(), e.getValue());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "请求参数格式错误");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 静态资源 404（/uploads/… 文件不存在等）：常见且无害（克隆环境/换机后历史附件缺失、文件被清理），
     *  返回 404 即可——走通用兜底会打 ERROR 全栈刷屏。 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.info("静态资源不存在：{}", e.getResourcePath());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "资源不存在");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** 空指针（多为公开接口缺登录属性/配置缺失等状态不完整）：外部审查 2026-10-11 指出
     *  公开前缀下此类异常会裸 500。统一收敛为 400 + 泛化文案，堆栈只进服务端日志。 */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNpe(NullPointerException e) {
        log.warn("NullPointerException：{}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "请求参数或状态不完整");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
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
