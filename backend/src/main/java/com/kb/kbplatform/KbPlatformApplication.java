/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // 启用定时任务：回收站超期自动清理（RecycleCleanupTask）
public class KbPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbPlatformApplication.class, args);
    }
}
