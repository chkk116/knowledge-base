package com.kb.kbplatform.security;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词（和谐词）过滤：启动时从 classpath 下的 sensitive-words.txt 加载词表。
 * - 文件每行一个词；以 # 开头的行为注释；空行忽略。
 * - 缺失词表文件不会阻断启动，仅退化为「不拦截」。
 * 提供 contains / find（命中词列表）/ mask（替换为等长 *）三种用法。
 */
@Component
public class SensitiveWordFilter {

    private final List<String> words = new ArrayList<>();

    @PostConstruct
    public void init() {
        try {
            var res = new ClassPathResource("sensitive-words.txt");
            if (res.exists()) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String w = line.trim();
                        if (!w.isEmpty() && !w.startsWith("#")) words.add(w);
                    }
                }
            }
        } catch (Exception e) {
            // 词表加载失败不阻断启动
        }
    }

    /** 是否包含任一敏感词 */
    public boolean contains(String text) {
        if (text == null || text.isEmpty() || words.isEmpty()) return false;
        for (String w : words) {
            if (!w.isEmpty() && text.contains(w)) return true;
        }
        return false;
    }

    /** 返回命中的敏感词列表（去重） */
    public List<String> find(String text) {
        List<String> hit = new ArrayList<>();
        if (text == null || text.isEmpty()) return hit;
        for (String w : words) {
            if (!w.isEmpty() && text.contains(w) && !hit.contains(w)) hit.add(w);
        }
        return hit;
    }

    /** 把命中的词替换成等长的 *（用于「和谐」展示，可选） */
    public String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (String w : words) {
            if (!w.isEmpty()) out = out.replace(w, "*".repeat(w.length()));
        }
        return out;
    }
}
