/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.BannedWord;
import com.kb.kbplatform.domain.BannedWordLibrary;
import com.kb.kbplatform.repository.BannedWordLibraryRepository;
import com.kb.kbplatform.repository.BannedWordRepository;
import com.kb.kbplatform.security.SensitiveWordFilter;
import com.kb.kbplatform.security.TokenClaims;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.*;

/**
 * 违禁词 / 词库管理（仅 BANNED_WORDS_MANAGE 权限或 ADMIN）。
 * - 词库（BannedWordLibrary）：可分组建库、启停「选择加载」、删除级联词条。
 * - 词条：按词库服务端分页，增删后热刷新过滤缓存，评论拦截 / 审核检查即时生效。
 */
@RestController
@RequestMapping("/api/admin/banned-words")
public class BannedWordAdminController {

    private final BannedWordRepository repo;
    private final BannedWordLibraryRepository libRepo;
    private final SensitiveWordFilter filter;

    public BannedWordAdminController(BannedWordRepository repo,
                                     BannedWordLibraryRepository libRepo,
                                     SensitiveWordFilter filter) {
        this.repo = repo;
        this.libRepo = libRepo;
        this.filter = filter;
    }

    private void requirePerm(TokenClaims c) {
        if (c == null) throw new org.springframework.web.server.ResponseStatusException(UNAUTHORIZED, "请先登录");
        if (!"ADMIN".equals(c.role()) && !c.hasPerm("BANNED_WORDS_MANAGE"))
            throw new org.springframework.web.server.ResponseStatusException(FORBIDDEN, "需要违禁词管理权限");
    }

    // ===== 词库 =====

    /** 词库列表（含每个词库的词数） */
    @GetMapping("/libraries")
    public List<Map<String, Object>> listLibraries(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c);
        return libRepo.findAll().stream().map(lib -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", lib.getId());
            m.put("name", lib.getName());
            m.put("description", lib.getDescription());
            m.put("enabled", lib.isEnabled());
            m.put("builtin", lib.isBuiltin());
            m.put("priority", lib.getPriority());
            m.put("wordCount", repo.countByLibraryId(lib.getId()));
            m.put("createdAt", lib.getCreatedAt());
            return m;
        }).toList();
    }

    /** 新建词库 */
    @PostMapping("/libraries")
    public Map<String, Object> createLibrary(@RequestAttribute("kbUser") TokenClaims c,
                                             @RequestBody Map<String, String> body) {
        requirePerm(c);
        String name = body.get("name");
        if (name == null || name.isBlank()) throw new org.springframework.web.server.ResponseStatusException(BAD_REQUEST, "词库名称不能为空");
        name = name.trim();
        if (libRepo.findByName(name).isPresent())
            throw new org.springframework.web.server.ResponseStatusException(CONFLICT, "词库「" + name + "」已存在");
        BannedWordLibrary lib = libRepo.save(new BannedWordLibrary(name, body.getOrDefault("description", "").trim(), true, false));
        return toMap(lib, 0L);
    }

    /** 更新词库（名称 / 描述 / 优先级 / 启用开关） */
    @PutMapping("/libraries/{id}")
    public Map<String, Object> updateLibrary(@RequestAttribute("kbUser") TokenClaims c,
                                             @PathVariable Long id,
                                             @RequestBody Map<String, Object> body) {
        requirePerm(c);
        BannedWordLibrary lib = libRepo.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(NOT_FOUND, "词库不存在"));
        if (body.containsKey("name")) lib.setName(((String) body.get("name")).trim());
        if (body.containsKey("description")) lib.setDescription((String) body.get("description"));
        if (body.containsKey("priority")) lib.setPriority(((Number) body.get("priority")).intValue());
        if (body.containsKey("enabled")) {
            boolean next = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
            if (next != lib.isEnabled()) {
                lib.setEnabled(next);
                filter.refresh();   // 启停会影响实时过滤
            }
        }
        lib = libRepo.save(lib);
        return toMap(lib, repo.countByLibraryId(lib.getId()));
    }

    /** 删除词库（级联删除其下词条） */
    @DeleteMapping("/libraries/{id}")
    public Map<String, Object> deleteLibrary(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        if (!libRepo.existsById(id)) throw new org.springframework.web.server.ResponseStatusException(NOT_FOUND, "词库不存在");
        repo.deleteByLibraryId(id);
        libRepo.deleteById(id);
        filter.refresh();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    // ===== 词条 =====

    /** 词条分页列表（可选按词库过滤；不传 libraryId 则返回全部） */
    @GetMapping
    public Map<String, Object> list(@RequestAttribute("kbUser") TokenClaims c,
                                    @RequestParam(required = false) Long libraryId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "15") int size) {
        requirePerm(c);
        Pageable pg = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<BannedWord> result = (libraryId == null) ? repo.findAll(pg) : repo.findByLibraryId(libraryId, pg);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", result.getContent());
        m.put("total", result.getTotalElements());
        m.put("page", result.getNumber());
        m.put("size", result.getSize());
        return m;
    }

    /** 新增词条：支持单行或换行批量；重复词自动跳过 */
    @PostMapping
    public Map<String, Object> add(@RequestAttribute("kbUser") TokenClaims c,
                                   @RequestBody Map<String, Object> body) {
        requirePerm(c);
        Object lid = body.get("libraryId");
        if (lid == null) throw new org.springframework.web.server.ResponseStatusException(BAD_REQUEST, "请选择目标词库");
        Long libraryId = Long.valueOf(lid.toString());
        if (!libRepo.existsById(libraryId)) throw new org.springframework.web.server.ResponseStatusException(NOT_FOUND, "词库不存在");
        String raw = (String) body.get("words");
        if (raw == null || raw.isBlank()) throw new org.springframework.web.server.ResponseStatusException(BAD_REQUEST, "词不能为空");
        // 先收集本批候选词，再一次性批量判重（旧实现是 findAll() 把整张词表拉进内存）
        List<String> candidates = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : raw.split("[\\r\\n]+")) {
            String w = line.trim();
            if (w.isEmpty()) continue;
            if (!seen.add(w)) { duplicated.add(w); continue; }   // 本批内重复
            candidates.add(w);
        }
        Set<String> existing = candidates.isEmpty() ? Collections.emptySet() : repo.findExistingWords(candidates);
        List<BannedWord> toSave = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (String w : candidates) {
            if (existing.contains(w)) { duplicated.add(w); continue; }
            toSave.add(new BannedWord(w, libraryId));
            added.add(w);
        }
        if (!toSave.isEmpty()) repo.saveAll(toSave);
        filter.refresh();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("added", added);
        m.put("duplicated", duplicated);
        m.put("addedCount", added.size());
        return m;
    }

    /** 离线词库导入：把「下载的在线词库文件」或粘贴的文本批量导入某词库。
     *  - 文本：每行一个词（兼容 txt / csv 单列 / 逗号分隔）
     *  - 文件：上传 .txt/.csv/.json（每行一个词）二选一
     *  重复词（与全库已有词或本批内重复）自动跳过，批量写入后热刷新过滤缓存。 */
    @PostMapping("/import")
    public Map<String, Object> importWords(@RequestAttribute("kbUser") TokenClaims c,
                                           @RequestParam Long libraryId,
                                           @RequestParam(required = false) String content,
                                           @RequestParam(required = false) MultipartFile file) {
        requirePerm(c);
        if (!libRepo.existsById(libraryId)) throw new org.springframework.web.server.ResponseStatusException(NOT_FOUND, "词库不存在");
        String text = content;
        if ((text == null || text.isBlank()) && file != null && !file.isEmpty()) {
            try {
                text = new String(file.getBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new org.springframework.web.server.ResponseStatusException(BAD_REQUEST, "读取上传文件失败");
            }
        }
        if (text == null || text.isBlank()) throw new org.springframework.web.server.ResponseStatusException(BAD_REQUEST, "请提供词库文本或上传文件");
        // 兼容逗号分隔：先按行、行内再按逗号拆分；
        // 先收集候选词再一次性批量判重（旧实现是 findAll() 把整张词表拉进内存）
        List<String> candidates = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : text.split("\\R")) {
            for (String part : line.split("[,，]")) {
                String w = part.trim();
                if (w.isEmpty()) continue;
                if (!seen.add(w)) {
                    if (!duplicated.contains(w)) duplicated.add(w);   // 本批内重复
                    continue;
                }
                candidates.add(w);
            }
        }
        Set<String> existing = candidates.isEmpty() ? Collections.emptySet() : repo.findExistingWords(candidates);
        List<BannedWord> toSave = new ArrayList<>();
        for (String w : candidates) {
            if (existing.contains(w)) {
                if (!duplicated.contains(w)) duplicated.add(w);
                continue;
            }
            toSave.add(new BannedWord(w, libraryId));
        }
        if (!toSave.isEmpty()) repo.saveAll(toSave);
        filter.refresh();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("addedCount", toSave.size());
        m.put("duplicatedCount", duplicated.size());
        m.put("totalInLibrary", repo.countByLibraryId(libraryId));
        return m;
    }

    /** 删除词条 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        if (!repo.existsById(id)) throw new org.springframework.web.server.ResponseStatusException(NOT_FOUND, "词条不存在");
        repo.deleteById(id);
        filter.refresh();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    // ===== 工具 =====

    private Map<String, Object> toMap(BannedWordLibrary lib, Long wordCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", lib.getId());
        m.put("name", lib.getName());
        m.put("description", lib.getDescription());
        m.put("enabled", lib.isEnabled());
        m.put("builtin", lib.isBuiltin());
        m.put("priority", lib.getPriority());
        m.put("wordCount", wordCount);
        m.put("createdAt", lib.getCreatedAt());
        return m;
    }
}
