/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回收站自动清理：软删除文档会一直堆积在库里，靠人工「彻底删除」不现实。
 * 本任务按 {@code kb.recycle.retention-days} 的默认保留期，每天定时把超期的软删除文档物理清除。
 *
 * 设计要点：
 * - 每条独立事务（TransactionTemplate）：某条因外键/关联数据删不掉时只跳过它，不影响其余清理，
 *   也不会因单条异常中断整批。注意不能用同类内 @Transactional 自调用（Spring AOP 不生效）。
 * - 每条都写审计（actorName=「系统」），可追溯——自动清理同样进审计流水。
 * - 关闭方式：{@code kb.recycle.cleanup-enabled=false}，或 retention-days <= 0（保留期非正表示永不自动清理）。
 */
@Component
public class RecycleCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(RecycleCleanupTask.class);
    private static final String SYSTEM_ACTOR = "系统";

    private final DocumentRepository docRepo;
    private final AuditService auditService;
    private final TransactionTemplate tx;
    private final DocumentService documentService;

    @Value("${kb.recycle.cleanup-enabled:true}")
    private boolean cleanupEnabled;

    @Value("${kb.recycle.retention-days:30}")
    private int retentionDays;

    public RecycleCleanupTask(DocumentRepository docRepo, AuditService auditService, TransactionTemplate tx,
                              DocumentService documentService) {
        this.docRepo = docRepo;
        this.auditService = auditService;
        this.tx = tx;
        this.documentService = documentService;
    }

    /** 每天 03:00 执行（cron 可通过 kb.recycle.cron 覆盖） */
    @Scheduled(cron = "${kb.recycle.cron:0 0 3 * * *}")
    public void run() {
        if (!cleanupEnabled) return;
        if (retentionDays <= 0) {
            log.info("[回收站清理] 保留期非正（{}天），跳过自动清理", retentionDays);
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<Object[]> expired;
        try {
            expired = docRepo.findExpiredRecycled(cutoff);
        } catch (Exception e) {
            log.warn("[回收站清理] 查询超期文档失败：{}", e.getMessage());
            return;
        }
        if (expired == null || expired.isEmpty()) {
            log.info("[回收站清理] 无超期文档（保留期 {} 天）", retentionDays);
            return;
        }
        int ok = 0, fail = 0;
        for (Object[] row : expired) {
            if (row == null || row.length == 0) continue;
            final Long id = toLong(row[0]);
            final String title = row.length > 1 && row[1] != null ? String.valueOf(row[1]) : "";
            if (id == null) continue;
            try {
                // 复用 DocumentService.purge：同步清理评论/点赞/收藏/版本/订阅等关联数据 + Meili 搜索索引，
                // 此前 hardDeleteById 直删文档行，留下孤儿关联与失效索引（2026-10-13 审查 H3）。
                // purge 内含 @Modifying 批量删除，仍在事务模板内执行，保持每条独立事务的失败隔离。
                tx.execute(status -> { documentService.purge(id, null, true); return 1; });
                auditService.log(null, SYSTEM_ACTOR, "AUTO_PURGE_DOC", "DOCUMENT", id, title,
                        "回收站超期自动清理：彻底删除文档《" + clip(title) + "》（超过保留期 " + retentionDays + " 天）");
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("[回收站清理] 文档 {} 删除失败（可能仍被关联数据引用）：{}", id, e.getMessage());
            }
        }
        log.info("[回收站清理] 完成：清理 {} 条，失败 {} 条（保留期 {} 天，截止 {}）", ok, fail, retentionDays, cutoff);
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clip(String t) {
        if (t == null) return "";
        t = t.trim();
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
    }
}
