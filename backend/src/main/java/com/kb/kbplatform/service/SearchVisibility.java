/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 搜索/AI 检索共用的 Meili 命中可见性工具（F10）。
 * Meili 索引删除失败时，下架/封禁/删除的文档仍可能留在索引里；
 * 统一规则：id 无法解析或数据库当前不可见（非 PUBLISHED/在回收站）的条目一律过滤——
 * 索引与数据库不一致时以数据库为准。此前 SearchService 与 RagService 各写一份、细节有差异，现收敛到此处。
 */
final class SearchVisibility {

    private SearchVisibility() {}

    /** 提取 hits 里可解析为文档 id 的值 */
    static Set<Long> extractIds(List<Map<String, Object>> hits) {
        Set<Long> ids = new HashSet<>();
        if (hits == null) return ids;
        for (Map<String, Object> h : hits) {
            if (h.get("id") == null) continue;
            try { ids.add(Long.valueOf(String.valueOf(h.get("id")))); } catch (Exception e) { /* ignore */ }
        }
        return ids;
    }

    /**
     * 按数据库可见集合过滤命中条目。
     * @param limit >0 时最多返回 limit 条（RAG 取 TOP_K 用）；0 表示不截断
     */
    static List<Map<String, Object>> filterVisible(List<Map<String, Object>> hits, Set<Long> visible, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> h : hits) {
            if (limit > 0 && out.size() >= limit) break;
            Long hid = null;
            try { hid = h.get("id") == null ? null : Long.valueOf(String.valueOf(h.get("id"))); } catch (Exception e) { /* ignore */ }
            if (hid == null || !visible.contains(hid)) continue;   // id 缺失/不可解析或索引过期条目：一律不输出
            out.add(h);
        }
        return out;
    }
}
