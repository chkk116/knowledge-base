package com.kb.kbplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 协作中继（collab-relay）主动通知器（F04）。
 *
 * 背景：成员权限变更只改数据库，中继既有长连接不会失效（原来靠 15s 轮询兜底）。
 * 在事务提交后 POST /revoke，中继按 shareId/userId（可选 docId）重查有效权限，
 * 不直接信任通知携带的旧权限值，避免事务回滚或通知乱序扩大权限。
 *
 * 安全：请求体用 HMAC-SHA256(共享密钥, body) 签名放在 X-KB-Relay-Sign，中继验签后才执行。
 * 可靠性：提交后异步发送，失败记录告警；写入消息有实时复检，空闲读取连接有周期复检。
 * 通知失败时空闲连接的撤权存在复检间隔，不能承诺零延迟。
 */
@Component
public class RelayNotifier {

    private static final Logger log = LoggerFactory.getLogger(RelayNotifier.class);
    private final String relayUrl;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public RelayNotifier(@Value("${kb.collab.relay-url:http://127.0.0.1:1234}") String relayUrl,
                         com.kb.kbplatform.security.TokenSecretProvider secretProvider) {
        this.relayUrl = (relayUrl == null || relayUrl.isBlank()) ? "http://127.0.0.1:1234" : relayUrl.trim();
        this.secret = secretProvider.get();
    }

    /** 兼容空间权限调用方；perm 只是历史参数，授权以提交后的数据库状态为准。 */
    public void permissionChanged(long shareId, long userId, String perm) {
        notifyAfterCommit(shareId, userId, null);
    }

    public void documentPermissionChanged(long shareId, long docId, long userId) {
        notifyAfterCommit(shareId, userId, docId);
    }

    private void notifyAfterCommit(long shareId, long userId, Long docId) {
        String body = "{\"shareId\":" + shareId + ",\"userId\":" + userId
                + (docId == null ? "" : ",\"docId\":" + docId) + "}";
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { sendAsync(body); }
            });
        } else {
            sendAsync(body);
        }
    }

    // 包可见以便纯单测拦截发送；无需启动 HTTP 服务或读取任何部署凭据。
    void sendAsync(String body) {
        String sign = hmac(body);
        Thread.startVirtualThread(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(relayUrl + "/revoke"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .header("X-KB-Relay-Sign", sign)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<Void> result = http.send(req, HttpResponse.BodyHandlers.discarding());
                if (result.statusCode() < 200 || result.statusCode() >= 300) {
                    log.warn("协作权限复检通知失败，HTTP {}，等待中继周期复检", result.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("协作权限复检通知被中断，等待中继周期复检");
            } catch (Exception e) {
                log.warn("协作权限复检通知未送达，等待中继周期复检（{}）", e.getClass().getSimpleName());
            }
        });
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }
}
