/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 腾讯云 IM/Chat UserSig 生成（TLS 2.0 格式）。
 *
 * 与官方 TLSSigAPIv2.java 同一算法：把 identifier/sdkappid/time/expire 拼成多行文本，
 * 用 SecretKey 做 HmacSHA256，签名 base64 后连同字段一起放进 JSON，整体 URL-safe base64。
 * 集成中心的「测试连接」会真实调用一次 REST API，签名错了会被服务端立刻发现（错误码带原因）。
 */
public final class TrtcUserSig {

    private TrtcUserSig() { }

    /** @param expireSeconds 有效期（官方推荐 180 天 = 15552000） */
    public static String gen(long sdkAppId, String secretKey, String userId, long expireSeconds) {
        long currTime = System.currentTimeMillis() / 1000L;
        String content = "TLS.identifier:" + userId + "\n"
                + "TLS.sdkappid:" + sdkAppId + "\n"
                + "TLS.time:" + currTime + "\n"
                + "TLS.expire:" + expireSeconds + "\n";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("TLS.ver", "2.0");
            doc.put("TLS.identifier", userId);
            doc.put("TLS.sdkappid", sdkAppId);
            doc.put("TLS.expire", expireSeconds);
            doc.put("TLS.time", currTime);
            doc.put("TLS.sig", sig);
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            byte[] json = om.writeValueAsBytes(doc);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("UserSig 生成失败：" + e.getMessage(), e);
        }
    }
}
