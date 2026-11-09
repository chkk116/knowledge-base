/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AiFeedbackAction;
import com.kb.kbplatform.domain.AiQaLog;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.AiFeedbackActionRepository;
import com.kb.kbplatform.repository.AiQaLogRepository;
import com.kb.kbplatform.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.*;

/**
 * AI 问答反馈与知识缺口统计。
 *
 * 数据来源是 {@link AiQaLog}：AiConversationService 在每次回答后落一行，
 * 前端只负责把「赞/踩 + 原因」回填到对应行（qaId），不信任前端上报的问答正文。
 */
@Service
public class AiFeedbackService {

    private static final int MAX_QUESTION = 2000;
    private static final int MAX_ANSWER = 4000;
    private static final int MAX_SOURCES_JSON = 8000;
    private static final int MAX_REASON = 500;

    private final AiQaLogRepository repo;
    private final UserRepository userRepo;
    private final AiFeedbackActionRepository actionRepo;

    public AiFeedbackService(AiQaLogRepository repo, UserRepository userRepo,
                             AiFeedbackActionRepository actionRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.actionRepo = actionRepo;
    }

    /** 回答落库：返回 qaId，前端用它回填赞/踩。任何异常都不能影响正常问答。 */
    @Transactional(rollbackFor = Exception.class)
    public Long record(Long userId, String question, String answer, String mode, Object sources) {
        try {
            boolean general = "general".equalsIgnoreCase(mode);
            int sourceCount = (sources instanceof List<?> l) ? l.size() : 0;
            AiQaLog log = new AiQaLog();
            log.setUserId(userId);
            log.setQuestion(truncate(question, MAX_QUESTION));
            log.setAnswer(truncate(answer, MAX_ANSWER));
            log.setMode(mode == null ? "kb" : mode);
            log.setHit(!general && sourceCount > 0);
            log.setSourceCount(sourceCount);
            if (sources instanceof List && !((List<?>) sources).isEmpty()) {
                try {
                    log.setSourcesJson(truncate(JsonMapper.builder().build().writeValueAsString(sources), MAX_SOURCES_JSON));
                } catch (Exception ignore) { /* 序列化失败不影响记录 */ }
            }
            return repo.save(log).getId();
        } catch (Exception e) {
            // 反馈是辅助数据，写失败不能影响用户拿到回答
            return null;
        }
    }

    /** 用户对某条回答点赞/踩；只能评价自己的记录。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rate(Long userId, Long qaId, Integer rating, String reason) {
        if (qaId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 qaId");
        // 0 = 取消评价：rating/reason/ratedAt 全部清空，前端「再点一次同项」即取消
        if (rating == null || (rating != 1 && rating != -1 && rating != 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating 只能是 1（赞）、-1（踩）或 0（取消评价）");
        }
        AiQaLog log = repo.findByIdAndUserId(qaId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或无权评价"));
        log.setRating(rating == 0 ? null : rating);
        log.setReason(rating == -1 ? truncate(reason, MAX_REASON) : null);
        log.setRatedAt(rating == 0 ? null : LocalDateTime.now());
        repo.save(log);
        return Map.of("ok", true, "qaId", qaId, "rating", rating);
    }

    /** 处理状态全量下发（条目少，一次给全，前端按 key 查） */
    @Transactional(readOnly = true)
    public Map<String, Object> actions() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (AiFeedbackAction a : actionRepo.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", a.getStatus());
            m.put("note", a.getNote());
            m.put("at", a.getUpdatedAt() == null ? null : a.getUpdatedAt().toString());
            out.put(a.getActionKey(), m);
        }
        return out;
    }

    /**
     * 写入 / 删除处理状态。status 为空 = 删掉本行（回到待处理）。
     * 只认 done / ignored，key 限长，note 截断 —— 不信任前端上报。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveAction(Long userId, String key, String status, String note) {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action key 非法");
        }
        if (status == null || status.isBlank()) {
            actionRepo.findByActionKey(key).ifPresent(actionRepo::delete);
            return Map.of("ok", true, "deleted", true);
        }
        if (!status.equals("done") && !status.equals("ignored")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 只能是 done / ignored");
        }
        AiFeedbackAction a = actionRepo.findByActionKey(key).orElseGet(() -> new AiFeedbackAction(key));
        a.setStatus(status);
        a.setNote(note == null ? null : truncate(note, 200));
        a.setActorId(userId);
        a.setUpdatedAt(LocalDateTime.now());
        actionRepo.save(a);
        return Map.of("ok", true, "key", key, "status", status);
    }

    /** 前端 key：聚合未命中 = 'm:' + 问题文本（截 120 字），与数据库侧回测必须一致 */
    private static String missKey(String question) {
        String q = String.valueOf(question == null ? "" : question).trim();
        return "m:" + q.substring(0, Math.min(120, q.length()));
    }

    /** 管理端看板：命中率 / 赞踩 / 高频未命中 / 最近差评 + 处理状态与补文档回测。 */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(int days) {
        int d = Math.max(1, Math.min(days, 90));
        LocalDateTime since = LocalDateTime.now().minusDays(d);

        long total = repo.count();
        long kbTotal = repo.countByModeNot("general");
        long hit = repo.countByHitTrue();
        long miss = repo.countByHitFalse();
        long positive = repo.countByRating(1);
        long negative = repo.countByRating(-1);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("days", d);
        res.put("total", total);
        res.put("kbTotal", kbTotal);
        res.put("hit", hit);
        res.put("miss", miss);
        res.put("hitRate", kbTotal > 0 ? Math.round(hit * 100.0 / kbTotal) : 0);
        res.put("positive", positive);
        res.put("negative", negative);

        // 高频未命中问题（近 N 天）：附带处理状态 + 补文档后是否复现
        Map<String, AiFeedbackAction> actionMap = new HashMap<>();
        for (AiFeedbackAction a : actionRepo.findAll()) actionMap.put(a.getActionKey(), a);
        List<Map<String, Object>> topMissed = new ArrayList<>();
        for (Object[] row : repo.topMissedSince(since, PageRequest.of(0, 20))) {
            String q = String.valueOf(row[0]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", q);
            m.put("count", ((Number) row[1]).longValue());
            AiFeedbackAction a = actionMap.get(missKey(q));
            if (a != null) {
                m.put("status", a.getStatus());
                m.put("actionNote", a.getNote());
                // 回测：标为「已处理」之后，该问题是否又出现未命中
                //   verified=true  → 补文档生效（处理后再没出现）
                //   recurred=true  → 又出现，说明补得不够或检索仍有问题
                if ("done".equals(a.getStatus()) && a.getUpdatedAt() != null) {
                    long recur = repo.countByQuestionAndHitFalseAndCreatedAtAfter(q, a.getUpdatedAt());
                    m.put("recurCount", recur);
                    m.put("recurred", recur > 0);
                    m.put("verified", recur == 0);
                }
            }
            topMissed.add(m);
        }
        res.put("topMissed", topMissed);

        // 最近未命中（原始清单，便于看具体问法）
        List<Map<String, Object>> recentMissed = new ArrayList<>();
        for (AiQaLog l : repo.findTop50ByHitFalseAndModeNotOrderByIdDesc("general")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("question", l.getQuestion());
            m.put("mode", l.getMode());
            m.put("createdAt", l.getCreatedAt());
            recentMissed.add(m);
        }
        res.put("recentMissed", recentMissed);

        // 最近差评（附原因与用户，便于定位是内容问题还是模型问题）
        List<AiQaLog> negLogs = repo.findTop20ByRatingOrderByIdDesc(-1);
        Map<Long, String> names = userNameMap(negLogs);
        List<Map<String, Object>> recentNegative = new ArrayList<>();
        for (AiQaLog l : negLogs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("question", l.getQuestion());
            m.put("answer", truncate(l.getAnswer(), 300));
            m.put("reason", l.getReason());
            m.put("user", names.getOrDefault(l.getUserId(), "用户#" + l.getUserId()));
            m.put("createdAt", l.getCreatedAt());
            m.put("ratedAt", l.getRatedAt());
            recentNegative.add(m);
        }
        res.put("recentNegative", recentNegative);
        return res;
    }

    private Map<Long, String> userNameMap(List<AiQaLog> logs) {
        if (logs.isEmpty()) return Map.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (AiQaLog l : logs) ids.add(l.getUserId());
        Map<Long, String> out = new HashMap<>();
        for (User u : userRepo.findAllById(ids)) {
            String n = u.getDisplayName();
            out.put(u.getId(), (n == null || n.isBlank()) ? u.getUsername() : n);
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
