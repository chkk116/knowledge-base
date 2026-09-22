/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;

/**
 * 全局分页参数收敛（安全加固）：
 *  size 无上限时接口可被一次拖走全量数据（实测 size=9999 直接生效，既是信息批量泄露也是 DoS 面）；
 *  page 为负数时越界。这里在请求进入控制器前统一夹取，避免每个控制器各写一遍。
 */
public class PageParamFilter implements Filter {

    /** 单页最大条数：业务上 100 已足够，超出一律压回 100 */
    static final int MAX_SIZE = 100;
    static final int DEFAULT_SIZE = 20;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
            chain.doFilter(new SanitizedRequest(http), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    static final class SanitizedRequest extends HttpServletRequestWrapper {
        SanitizedRequest(HttpServletRequest request) { super(request); }

        @Override
        public String getParameter(String name) {
            String v = super.getParameter(name);
            if (v == null) return null;
            if ("size".equals(name)) return String.valueOf(clampSize(v));
            if ("page".equals(name)) return String.valueOf(clampPage(v));
            return v;
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] vs = super.getParameterValues(name);
            if (vs == null) return null;
            if (!"size".equals(name) && !"page".equals(name)) return vs;
            String[] out = new String[vs.length];
            for (int i = 0; i < vs.length; i++) {
                out[i] = "size".equals(name) ? String.valueOf(clampSize(vs[i])) : String.valueOf(clampPage(vs[i]));
            }
            return out;
        }

        private static int clampSize(String raw) {
            try {
                int n = Integer.parseInt(raw.trim());
                if (n <= 0) return DEFAULT_SIZE;
                return Math.min(n, MAX_SIZE);
            } catch (Exception e) {
                return DEFAULT_SIZE;   // 非数字：回落到默认，不再把解析异常抛给控制器
            }
        }

        private static int clampPage(String raw) {
            try {
                int n = Integer.parseInt(raw.trim());
                return Math.max(n, 0);
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
