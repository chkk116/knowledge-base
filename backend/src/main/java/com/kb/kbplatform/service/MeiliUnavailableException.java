package com.kb.kbplatform.service;

/**
 * 标记 Meilisearch 当前不可用（未启用 / 服务宕机 / 网络异常）。
 * SearchService 捕获后回退到 MySQL LIKE，保证搜索功能永远可用。
 */
public class MeiliUnavailableException extends RuntimeException {
    public MeiliUnavailableException(String msg) {
        super(msg);
    }

    public MeiliUnavailableException(Throwable cause) {
        super(cause);
    }
}
