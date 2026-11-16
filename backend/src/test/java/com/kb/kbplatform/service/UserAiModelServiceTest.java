/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 用户私有模型 base-url 的 SSRF 防护：字符串黑名单之外还要挡 DNS 指向内网的域名。 */
class UserAiModelServiceTest {

    @Test
    @DisplayName("http/https 公网地址放行，非 http 与无主机地址拒绝")
    void schemeAndHost() {
        // 用公网 IP 字面量，避免单测依赖外网 DNS 可用性
        assertTrue(UserAiModelService.isSafeBaseUrl("https://8.8.8.8/v1"));
        assertTrue(UserAiModelService.isSafeBaseUrl("http://93.184.215.14"));
        assertFalse(UserAiModelService.isSafeBaseUrl("file:///etc/passwd"));
        assertFalse(UserAiModelService.isSafeBaseUrl("not a url"));
    }

    @Test
    @DisplayName("字符串层面的回环 / 内网 / 云元数据地址拒绝")
    void literalInternalHosts() {
        for (String h : new String[]{"localhost", "127.0.0.1", "0.0.0.0", "10.0.0.1",
                "192.168.1.1", "169.254.169.254", "172.16.0.1", "172.31.255.255",
                "foo.local", "foo.internal", "::1"}) {
            assertFalse(UserAiModelService.isSafeBaseUrl("https://" + h + "/v1"), h);
        }
        assertTrue(UserAiModelService.isSafeBaseUrl("https://172.32.0.1/v1"));
    }

    @Test
    @DisplayName("DNS 解析到回环的域名也必须拒绝（纯字符串检查的绕过点）")
    void dnsRebindingBlocked() {
        // localhost 的解析结果必然含回环地址；即使字符串黑名单被等价写法绕过，DNS 层也会拦住
        assertFalse(UserAiModelService.isSafeBaseUrl("http://LOCALHOST./v1"));
        assertFalse(UserAiModelService.isSafeBaseUrl("http://[::1]/v1"));
    }
}
