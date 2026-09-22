/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

/**
 * 业务性失败（而非程序异常）：例如评论内容 sanitize 后为空、命中敏感词。
 *
 * 语义：这类情况属于「用户这次输入不合规」，不是客户端请求格式错误，
 * 因此不该返回 HTTP 4xx（会在控制台留红字、也容易被误判成接口坏了），
 * 由 GlobalExceptionHandler 统一转成 200 + { ok:false, error } 交给前端提示。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}
