/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 直连后端时 XFF / X-Real-IP 不能伪造客户端 IP（否则 IP 锁定失效）。 */
class ClientIpTest {

    private HttpServletRequest req(String remote, String real, String xff) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRemoteAddr()).thenReturn(remote);
        when(r.getHeader("X-Real-IP")).thenReturn(real);
        when(r.getHeader("X-Forwarded-For")).thenReturn(xff);
        return r;
    }

    @Test
    @DisplayName("直连公网来源时只认 TCP 层地址，忽略伪造头")
    void ignoresSpoofedHeadersOnDirectConnection() {
        assertEquals("8.8.8.8", ClientIp.resolve(req("8.8.8.8", "1.2.3.4", "5.6.7.8")));
        assertEquals("8.8.8.8", ClientIp.resolve(req("8.8.8.8", null, "1.1.1.1")));
    }

    @Test
    @DisplayName("本机反代来源仍接受 X-Real-IP，其次 XFF 首段")
    void trustsLoopbackProxyHeaders() {
        assertEquals("1.2.3.4", ClientIp.resolve(req("127.0.0.1", "1.2.3.4", "9.9.9.9")));
        assertEquals("9.9.9.9", ClientIp.resolve(req("0:0:0:0:0:0:0:1", null, "9.9.9.9, 10.0.0.1")));
    }

    @Test
    @DisplayName("无头或空请求安全回退")
    void nullSafe() {
        assertNull(ClientIp.resolve(null));
        assertEquals("127.0.0.1", ClientIp.resolve(req("127.0.0.1", "", "  ")));
    }
}
