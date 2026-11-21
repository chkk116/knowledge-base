/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.kbplatform.security.TrtcUserSig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TRTC Chat 离线推送适配器（第一单品）。
 *
 * 消息本体永远走自有后端（PG + /api/dm），这里只在目标用户离线时
 * 给他发一条轻量自定义消息（TIMCustomElem），由腾讯云代触发厂商离线推送。
 * App 点通知拉起后仍然从自有后端拉取，内容不落腾讯云的漫游记录之外。
 *
 * 配额（体验版）：REST 每天 1000 条 → 同一会话 10 分钟内只提醒一次（KvStore 节流）。
 */
@Service
public class TrtcPushAdapter {

    private static final Logger log = LoggerFactory.getLogger(TrtcPushAdapter.class);
    private static final String INTEGRATION_KEY = "trtc-chat";
    /** TRTC 用户映射：平台用户 id → kb_<id>（避免纯数字与邮箱式用户名冲突） */
    private static final String USER_PREFIX = "kb_";

    private final IntegrationService integrations;
    private final KvStore kv;
    private final ObjectMapper om = new ObjectMapper();
    private final RestTemplate rest = new RestTemplate();

    private volatile String cachedAdminSig;
    private volatile long cachedSigAt;

    public TrtcPushAdapter(IntegrationService integrations, KvStore kv) {
        this.integrations = integrations;
        this.kv = kv;
    }

    public boolean enabled() {
        return integrations.enabled(INTEGRATION_KEY);
    }

    /** 用户 id → TRTC 账号（App 端登录时用同一个映射） */
    public static String trtcUserId(Long platformUserId) {
        return USER_PREFIX + platformUserId;
    }

    /**
     * 离线私信提醒。返回 true = 已触发推送请求。
     * 节流：同一收件人 10 分钟内最多一条（无论来自哪个会话），别把每日 1000 条烧光。
     */
    public boolean notifyOfflineDm(Long toUserId, long unreadHint) {
        if (!enabled()) return false;
        if (!kv.allowSliding("trtcpush:throttle:" + toUserId, 1, 600_000L)) return false;
        Map<String, Object> data = new HashMap<>();
        data.put("type", "kb-dm-notify");
        data.put("count", unreadHint);
        Map<String, Object> content = new HashMap<>();
        content.put("Data", toJson(data));
        return sendCustom(toUserId, content, "你有新的私信");
    }

    /** 发自定义消息。offlineInfo 是厂商推送展示的标题/正文（离线时才会用到） */
    public boolean sendCustom(Long toUserId, Map<String, Object> msgContent, String offlineTitle) {
        Map<String, Object> cfg = integrations.load(INTEGRATION_KEY);
        if (cfg == null) return false;
        long sdkAppId;
        try {
            sdkAppId = Long.parseLong(String.valueOf(cfg.get("sdkAppId")));
        } catch (Exception e) {
            log.warn("TRTC 配置 sdkAppId 不合法");
            return false;
        }
        String secretKey = String.valueOf(cfg.get("secretKey"));
        String adminId = String.valueOf(cfg.getOrDefault("adminId", "administrator"));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("SyncOtherMachine", 1);        // 同步到发送方所有终端，保证在线设备也能看到
            body.put("To_Account", trtcUserId(toUserId));
            body.put("MsgRandom", (int) (Math.random() * Integer.MAX_VALUE));
            body.put("MsgBody", new Object[]{ Map.of("MsgType", "TIMCustomElem", "MsgContent", msgContent) });
            body.put("OfflinePushInfo", Map.of(
                    "PushFlag", 0,
                    "Title", offlineTitle == null ? "新的私信" : offlineTitle,
                    "Desc", "打开应用查看"));

            String url = "https://console.tim.qq.com/v4/openim/sendmsg?sdkappid=" + sdkAppId
                    + "&identifier=" + adminId
                    + "&usersig=" + adminSig(sdkAppId, secretKey, adminId)
                    + "&random=" + (int) (Math.random() * Integer.MAX_VALUE)
                    + "&contenttype=json";
            ResponseEntity<String> resp = rest.postForEntity(url, new HttpEntity<>(toJson(body), headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || String.valueOf(resp.getBody()).contains("\"ActionStatus\":\"FAIL\"")) {
                log.warn("TRTC 推送失败 {} -> {}: {}", toUserId, resp.getStatusCode(), resp.getBody());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("TRTC 推送异常 -> {}: {}", toUserId, e.getMessage());
            return false;
        }
    }

    /** 管理员 UserSig，缓存到过期前 1 小时（默认签 90 天，实际缓存按 30 天滚动重签） */
    private String adminSig(long sdkAppId, String secretKey, String adminId) {
        long now = System.currentTimeMillis();
        if (cachedAdminSig != null && now - cachedSigAt < 30L * 24 * 3600 * 1000) {
            return cachedAdminSig;
        }
        cachedAdminSig = TrtcUserSig.gen(sdkAppId, secretKey, adminId, 90L * 24 * 3600);
        cachedSigAt = now;
        return cachedAdminSig;
    }

    private String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
