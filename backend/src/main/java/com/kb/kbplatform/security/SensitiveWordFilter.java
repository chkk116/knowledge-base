/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import com.kb.kbplatform.domain.BannedWord;
import com.kb.kbplatform.domain.BannedWordLibrary;
import com.kb.kbplatform.repository.BannedWordLibraryRepository;
import com.kb.kbplatform.repository.BannedWordRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词（和谐词）过滤：DB 为唯一真源，按「词库」组织。
 * - 仅加载 enabled=true 的词库下的词条到内存热缓存（即「选择加载」：停用词库即对其下线，无需删词）。
 * - 管理页增删词 / 启停词库后调用 refresh() 热刷新，评论拦截 / 审核检查即时生效。
 * - 首次启动若无任何词库，自动建一个「默认词库」并把 classpath 下 sensitive-words.txt 的词 seed 进去。
 * 提供 contains / find / mask 三种用法。
 */
@Component
public class SensitiveWordFilter {

    // 内存词表（volatile 引用 + 整体替换，保证并发读安全）
    private volatile List<String> words = new ArrayList<>();

    private final BannedWordRepository bannedWordRepository;
    private final BannedWordLibraryRepository libraryRepository;

    public SensitiveWordFilter(BannedWordRepository bannedWordRepository,
                               BannedWordLibraryRepository libraryRepository) {
        this.bannedWordRepository = bannedWordRepository;
        this.libraryRepository = libraryRepository;
    }

    @PostConstruct
    public void init() {
        try {
            ensureDefaultLibrary();
            loadEnabled();
        } catch (Exception e) {
            // 表尚未迁移等异常：退化为空词表，不阻断启动
        }
    }

    /** 管理页增删词 / 启停词库后调用：重新加载启用中词库的词条 */
    public void refresh() {
        try {
            loadEnabled();
        } catch (Exception ignored) {
        }
    }

    /** 保证存在「默认词库」并至少补种一次静态词：
     *  1) 优先复用迁移 v117 建立的 builtin 默认词库；库表全空时才新建；
     *  2) 默认词库为空（如迁移时 kb_banned_word 本就为空）则从 sensitive-words.txt 补种，
     *     幂等：仅补入尚不存在的词，不会重复。 */
    private static final String DEFAULT_LIB_NAME = "默认词库";

    private void ensureDefaultLibrary() {
        // 优先按名称找到「默认词库」（不依赖 builtin 标记，避免被手动关闭后无法补种）；
        // 其次找任一 builtin 库；都找不到且库表为空时才新建。
        BannedWordLibrary lib = libraryRepository.findByName(DEFAULT_LIB_NAME)
                .or(() -> libraryRepository.findByBuiltinTrue().stream().findFirst())
                .orElseGet(() -> {
                    if (libraryRepository.count() == 0) {
                        return libraryRepository.save(
                                new BannedWordLibrary(DEFAULT_LIB_NAME, "系统默认词库", true, true));
                    }
                    return null;
                });
        if (lib == null) return;
        if (bannedWordRepository.countByLibraryId(lib.getId()) == 0) {
            seedFromFile(lib.getId());
        }
    }

    /** 仅把启用中的词库词条载入缓存 */
    private void loadEnabled() {
        List<Long> ids = libraryRepository.findByEnabledTrue().stream()
                .map(BannedWordLibrary::getId)
                .toList();
        if (ids.isEmpty()) {
            words = new ArrayList<>();
            return;
        }
        List<BannedWord> all = bannedWordRepository.findByLibraryIdIn(ids);
        replaceCache(all);
    }

    /** 首次为空时用旧静态文件 seed 一次（仅导入词，不改写文件） */
    private void seedFromFile(Long libraryId) {
        try {
            var res = new ClassPathResource("sensitive-words.txt");
            if (!res.exists()) return;
            List<BannedWord> seeds = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String w = line.trim();
                    if (!w.isEmpty() && !w.startsWith("#") && !bannedWordRepository.existsByWord(w)) {
                        seeds.add(new BannedWord(w, libraryId));
                    }
                }
            }
            if (!seeds.isEmpty()) bannedWordRepository.saveAll(seeds);
        } catch (Exception ignored) {
        }
    }

    private void replaceCache(List<BannedWord> all) {
        List<String> next = new ArrayList<>(all.size());
        for (BannedWord b : all) {
            if (b.getWord() != null && !b.getWord().isBlank()) next.add(b.getWord().trim());
        }
        words = next;
    }

    /** 是否包含任一敏感词 */
    public boolean contains(String text) {
        if (text == null || text.isEmpty() || words.isEmpty()) return false;
        for (String w : words) {
            if (!w.isEmpty() && isMatch(text, w)) return true;
        }
        return false;
    }

    /** 返回命中的敏感词列表（去重） */
    public List<String> find(String text) {
        List<String> hit = new ArrayList<>();
        if (text == null || text.isEmpty()) return hit;
        for (String w : words) {
            if (!w.isEmpty() && isMatch(text, w) && !hit.contains(w)) hit.add(w);
        }
        return hit;
    }

    /**
     * 是否命中：短 ASCII 字母/数字词（≤4，如 sb/tmd/cnm/mdzz）要求「词边界」，
     * 避免误伤 husband/absorb/subway 等正常词里包含的字母串；
     * 中文词与较长英文脏话仍按子串匹配（兼顾 fuckyou/shitty 等粘连变体）。
     * 边界判定：词两侧不能「同时」是 ASCII 字母/数字——只要有一侧是中文/标点/空格/首尾，即视为独立脏话命中。
     */
    private static boolean isMatch(String text, String word) {
        int idx = text.indexOf(word);
        while (idx >= 0) {
            if (!needsBoundary(word)) return true;            // 中文词 / 长英文词：子串即命中
            boolean beforeWord = idx > 0 && isAsciiWord(text.charAt(idx - 1));
            boolean afterWord = idx + word.length() < text.length()
                    && isAsciiWord(text.charAt(idx + word.length()));
            if (!(beforeWord && afterWord)) return true;      // 至少一侧非 ASCII 词字符 → 命中
            idx = text.indexOf(word, idx + 1);
        }
        return false;
    }

    /** 仅 ASCII 字母/数字视为「词字符」（用于边界判定；中文等一律不算，保证中文脏话子串命中） */
    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** 短 ASCII 字母/数字词才需要词边界保护，避免误伤正常英文单词里的字母串 */
    private static boolean needsBoundary(String word) {
        if (word.isEmpty() || word.length() > 4) return false;
        for (int i = 0; i < word.length(); i++) {
            if (!isAsciiWord(word.charAt(i))) return false;
        }
        return true;
    }

    /** 把命中的词替换成等长的 *（用于「和谐」展示，可选）；同样遵循词边界规则 */
    public String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (String w : words) {
            if (w.isEmpty() || !isMatch(out, w)) continue;
            out = out.replace(w, "*".repeat(w.length()));
        }
        return out;
    }
}
