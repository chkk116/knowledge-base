/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.bootstrap;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次启动的管理员引导（**与示例数据种子解耦，生产环境同样生效**）。
 *
 * <p>背景：生产 {@code application-prod.yml} 设 {@code kb.seed.enabled=false}，而 {@link DataInitializer}
 * 是唯一创建管理员的地方 —— 全新生产库启动后没有任何管理员，只能手动改库或先在 dev 建号再迁移。
 * 这里补上正规路径：设置 {@code KB_ADMIN_USERNAME} / {@code KB_ADMIN_PASSWORD} 后启动即可自动创建。</p>
 *
 * <p>语义：</p>
 * <ul>
 *   <li>只在该用户名**不存在**时创建；已存在则仅保证「角色为 ADMIN 且已启用」，**绝不覆盖密码**
 *       —— 否则运维改过密码后每次重启都会被打回环境变量里的旧值。</li>
 *   <li>未配置环境变量、且库里一个管理员都没有时，打印醒目的错误日志，明确告诉运维怎么引导。</li>
 * </ul>
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepo;
    private final String adminUsername;
    private final String adminPassword;

    public AdminBootstrap(UserRepository userRepo,
                          @Value("${kb.admin.username:${KB_ADMIN_USERNAME:}}") String adminUsername,
                          @Value("${kb.admin.password:${KB_ADMIN_PASSWORD:}}") String adminPassword) {
        this.userRepo = userRepo;
        this.adminUsername = adminUsername == null ? "" : adminUsername.trim();
        this.adminPassword = adminPassword == null ? "" : adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean hasUser = !adminUsername.isEmpty();
        boolean hasPwd = !adminPassword.isEmpty();

        if (hasUser && hasPwd) {
            ensureAdmin();
            return;
        }
        if (hasUser != hasPwd) {
            log.error("[引导] KB_ADMIN_USERNAME 与 KB_ADMIN_PASSWORD 必须成对设置，当前只设置了一个，已忽略。");
        }
        // 没有管理员 = 系统不可运维（进不去后台、改不了任何配置），必须让运维看见
        if (userRepo.countByRole("ADMIN") == 0) {
            log.error("[引导] 当前系统中没有任何管理员账号。"
                    + "请设置环境变量 KB_ADMIN_USERNAME 与 KB_ADMIN_PASSWORD 后重启，将自动创建首个管理员。");
        }
    }

    private void ensureAdmin() {
        User u = userRepo.findByUsername(adminUsername).orElse(null);
        if (u == null) {
            User created = new User();
            created.setUsername(adminUsername);
            created.setPassword(PasswordUtil.encode(adminPassword));
            created.setRole("ADMIN");
            created.setDisplayName(adminUsername);
            created.setEnabled(true);
            userRepo.save(created);
            log.info("[引导] 已按 KB_ADMIN_USERNAME 创建管理员账号：{}（请尽快登录并修改密码）", adminUsername);
            return;
        }
        boolean changed = false;
        // 已存在账号绝不提升角色/重新启用：开放注册场景下攻击者可抢注引导名（如 admin），
        // 若此处把既有账号提升为 ADMIN，运维一次重启即完成提权（2026-10-06 外部安全审查）。
        // 引导语义收敛为「只创建不存在的管理员」；改名/停用/降级走「用户管理」的正常流程。
        log.warn("[引导] 管理员用户名「{}」已被占用（非本次创建），不做任何变更；"
                + "如确需提权或恢复启用，请在「用户管理」中手动处理。", adminUsername);
    }
}
