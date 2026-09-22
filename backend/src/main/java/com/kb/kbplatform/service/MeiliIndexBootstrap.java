/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

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
            // 只取计数（COUNT 查询），不要为打条日志把全部已发布文档连正文拉进内存
            log.info("Meilisearch 索引已在启动时重建，已发布文档 {} 篇", docSvc.countPublic());
        } catch (Exception e) {
            log.warn("启动时重建 Meilisearch 索引失败（搜索将回退 MySQL，可在管理后台重试）：{}", e.getMessage());
        }
    }
}
