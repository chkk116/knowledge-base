/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 Yjs 更新（`kb_shared_document.yjs_state`）里提取纯文本。
 *
 * 背景：协同文档的正文只存在 Yjs 二进制状态里，`content` 列通常是空的
 * （只有「保存快照」时才写一次）。所以「拿文档正文」这件事在服务端没有现成路径，
 * 而 AI 出题、摘要这类功能又必须要文本。
 *
 * 实现范围：只做**顺序遍历 struct 流并收集 ContentString**，不重建 CRDT、
 * 不处理 DeleteSet、不做格式化。对「喂给模型当参考资料」这个用途足够；
 * 副作用是被多人交错编辑过的文档，段落顺序可能与阅读顺序略有出入。
 *
 * 解析失败时 {@link #extract} 会退化为「扫描可打印 UTF-8 片段」——宁可带点噪音，
 * 也不要因为一次格式没吃透就让整个功能报错。
 */
public final class YjsTextExtractor {

    private YjsTextExtractor() {}

    /** Yjs 里表示块级元素的类型名：遇到时换行，避免所有段落粘成一行 */
    private static final String[] BLOCK_TYPES = {
            "paragraph", "heading", "listItem", "tableCell", "tableRow", "codeBlock",
            "blockquote", "bulletList", "orderedList", "horizontalRule", "table"
    };

    /** 降级扫描时要丢掉的排版元数据（不是正文内容） */
    private static final String[] NOISE = {
            "default", "paragraph", "heading", "listItem", "bulletList", "orderedList",
            "tableCell", "tableRow", "table", "tableHeader", "codeBlock", "blockquote",
            "hardBreak", "horizontalRule", "textAlign", "textStyle", "bold", "italic",
            "underline", "strike", "link", "image", "level", "type", "href", "src", "alt",
    };

    public static String extract(byte[] update) {
        if (update == null || update.length == 0) return "";
        try {
            String precise = parse(update);
            if (!precise.isBlank()) return tidy(precise);
        } catch (Exception ignored) {
            // 结构没吃透就走降级路径
        }
        return tidy(scan(update));
    }

    // ===================== 精确解析 =====================

    private static String parse(byte[] data) {
        Reader r = new Reader(data);
        StringBuilder sb = new StringBuilder();
        int clients = (int) r.varUint();
        for (int c = 0; c < clients; c++) {
            int structs = (int) r.varUint();
            r.varUint();   // client id
            r.varUint();   // clock
            for (int i = 0; i < structs; i++) {
                readStruct(r, sb);
            }
        }
        return sb.toString();
    }

    private static void readStruct(Reader r, StringBuilder sb) {
        int info = r.u8();
        int ref = info & 0x1F;

        if (ref == 0 || ref == 10) {          // GC / Skip：只有长度
            r.varUint();
            return;
        }

        if ((info & 0x80) != 0) { r.varUint(); r.varUint(); }   // origin (left)
        if ((info & 0x40) != 0) { r.varUint(); r.varUint(); }   // rightOrigin
        if ((info & 0xC0) == 0) {                                // 没有 origin 才写 parent
            if (r.varUint() == 1) r.varString();
            else { r.varUint(); r.varUint(); }
            if ((info & 0x20) != 0) r.varString();               // parentSub
        }

        // contentRefs 下标与 yjs 源码一致：1=Deleted 2=JSON 3=Binary 4=String
        // 5=Embed 6=Format 7=Type 8=Any 9=Doc（0 与 10 是 GC/Skip，上面已处理）
        switch (ref) {
            case 1 -> r.varUint();                       // ContentDeleted
            case 2 -> {                                  // ContentJSON：len × string
                int n = (int) r.varUint();
                for (int i = 0; i < n; i++) r.varString();
            }
            case 3 -> r.varBytes();                      // ContentBinary
            case 4 -> {                                  // ContentString
                String s = r.varString();
                if (!s.isEmpty()) sb.append(s);
            }
            case 5 -> r.any();                           // ContentEmbed
            case 6 -> {                                  // ContentFormat：key + any
                r.varString();
                r.any();
            }
            case 7 -> {                                  // ContentType
                // typeRefs：0=Array 1=Map 2=Text 3=XmlElement 4=XmlFragment 5=XmlHook 6=XmlText
                // 只有 XmlElement / XmlHook 会额外写节点名
                int typeRef = (int) r.varUint();
                if (typeRef == 3 || typeRef == 5) {
                    String name = r.varString();
                    if (isBlock(name)) sb.append('\n');
                }
            }
            case 8 -> {                                  // ContentAny：len × any
                int n = (int) r.varUint();
                for (int i = 0; i < n; i++) r.any();
            }
            case 9 -> r.varString();                     // ContentDoc：guid
            default -> throw new IllegalStateException("unknown content ref " + ref);
        }
    }

    private static boolean isBlock(String typeRef) {
        for (String b : BLOCK_TYPES) if (typeRef.contains(b)) return true;
        return false;
    }

    // ===================== 降级扫描 =====================

    private static String scan(byte[] b) {
        StringBuilder out = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < b.length) {
            int x = b[i] & 0xFF;
            if (x >= 0x20 && x < 0x7F) {          // ASCII 可打印
                cur.append((char) x);
                i++;
                continue;
            }
            if (x >= 0xC0) {                      // UTF-8 多字节起始
                int len = (x & 0xE0) == 0xC0 ? 2 : (x & 0xF0) == 0xE0 ? 3 : (x & 0xF8) == 0xF0 ? 4 : 0;
                if (len > 0 && i + len <= b.length) {
                    String s = new String(b, i, len, StandardCharsets.UTF_8);
                    if (s.indexOf('\uFFFD') < 0) {
                        cur.append(s);
                        i += len;
                        continue;
                    }
                }
            }
            flush(cur, out);
            i++;
        }
        flush(cur, out);
        return out.toString();
    }

    private static void flush(StringBuilder cur, StringBuilder out) {
        if (cur.length() == 0) return;
        String s = cur.toString().trim();
        cur.setLength(0);
        if (s.length() < 2) return;
        for (String n : NOISE) if (s.equals(n)) return;
        out.append(s).append(' ');
    }

    private static String tidy(String s) {
        return s.replaceAll("[ \\t\\u00A0]+", " ")
                .replaceAll(" ?\\n[ \\n]*", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    // ===================== 字节读取器 =====================

    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) { this.data = data; }

        int u8() {
            if (pos >= data.length) throw new IllegalStateException("eof");
            return data[pos++] & 0xFF;
        }

        /** lib0 的 varuint：7 位一组，最高位表示还有后续 */
        long varUint() {
            long num = 0;
            int shift = 0;
            while (true) {
                int b = u8();
                num |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return num;
                shift += 7;
                if (shift > 63) throw new IllegalStateException("varuint too long");
            }
        }

        String varString() {
            int len = (int) varUint();
            if (len < 0 || pos + len > data.length) throw new IllegalStateException("bad string len");
            String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        byte[] varBytes() {
            int len = (int) varUint();
            if (len < 0 || pos + len > data.length) throw new IllegalStateException("bad bytes len");
            byte[] out = new byte[len];
            System.arraycopy(data, pos, out, 0, len);
            pos += len;
            return out;
        }

        /** lib0 writeAny 的类型标记（只用于跳过，不保留值） */
        void any() {
            int tag = u8();
            switch (tag) {
                case 127, 126, 121, 120 -> { /* undefined / null / false / true */ }
                case 125 -> varUint();              // 整数（带符号位）
                case 124 -> pos += 4;               // float32
                case 123 -> pos += 8;               // float64
                case 122 -> pos += 8;               // bigint64
                case 119, 118 -> varString();       // string / JSON 串
                case 117 -> {                       // 数组
                    int n = (int) varUint();
                    for (int i = 0; i < n; i++) any();
                }
                case 116 -> varBytes();             // Uint8Array
                default -> throw new IllegalStateException("unknown any tag " + tag);
            }
        }
    }
}
