/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资产逾期提醒（每日 09:30）。
 *
 * 借出中且应归还日期早于今天的记录，给借用人发站内通知；同一笔借用每天最多一次
 * （AssetBorrow.overdueNotifiedAt 去重）。cron 可通过 kb.asset.overdue-cron 覆盖。
 */
@Component
public class AssetOverdueTask {

    private static final Logger log = LoggerFactory.getLogger(AssetOverdueTask.class);

    private final AssetService assetService;

    public AssetOverdueTask(AssetService assetService) {
        this.assetService = assetService;
    }

    @Scheduled(cron = "${kb.asset.overdue-cron:0 30 9 * * *}")
    public void run() {
        try {
            int sent = assetService.notifyOverdue();
            if (sent > 0) log.info("资产逾期提醒：本次发送 {} 条", sent);
        } catch (Exception e) {
            // 定时任务异常不能影响其它调度
            log.warn("资产逾期提醒任务失败: {}", e.getMessage());
        }
    }
}
