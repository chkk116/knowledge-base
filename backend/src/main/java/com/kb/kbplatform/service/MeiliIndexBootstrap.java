package com.kb.kbplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动钩子：当 Meilisearch 启用时，在应用启动后自动把已发布文档灌入索引。
 * 这样「启用 Meili + 重启」即可让种子数据 / 历史文档立即可被检索，无需手动调接口。
 * 失败仅记日志，不影响启动（搜索会回退 MySQL）。
 */
@Component
public class MeiliIndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MeiliIndexBootstrap.class);

    private final MeiliSearchClient meili;
    private final DocumentService docSvc;

    public MeiliIndexBootstrap(MeiliSearchClient meili, DocumentService docSvc) {
        this.meili = meili;
        this.docSvc = docSvc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!meili.isEnabled()) return;
        try {
            meili.reindexAll(docSvc.listAll());
            log.info("Meilisearch 索引已在启动时重建，已发布文档 {} 篇", docSvc.listPublic().size());
        } catch (Exception e) {
            log.warn("启动时重建 Meilisearch 索引失败（搜索将回退 MySQL，可在管理后台重试）：{}", e.getMessage());
        }
    }
}
