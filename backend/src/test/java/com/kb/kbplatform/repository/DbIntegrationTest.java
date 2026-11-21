/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库层集成测试（真实 PostgreSQL）。
 *
 * 为什么单独存在：单元测试只覆盖纯规则层，而「条件更新抢占」「唯一约束」这类语义
 * 只有真库才能验（外部审计里 S-L7 指的就是这块空白）。
 *
 * 跑法（默认跳过，避免 CI/本地没有 PG 时构建失败）：
 * <pre>
 *   createdb -U kb kb_it
 *   PGPASSWORD=kb mvn test -Dtest=DbIntegrationTest -Dkb.it=jdbc:postgresql://localhost:5432/kb_it
 * </pre>
 * 测试自己建临时表、跑完删掉，不碰业务库。
 */
class DbIntegrationTest {

    private static final String URL = System.getProperty("kb.it", "");

    private Connection connect() throws Exception {
        Assumptions.assumeTrue(!URL.isBlank(), "未设置 -Dkb.it=jdbc:... 时跳过（需要真实 PostgreSQL）");
        return DriverManager.getConnection(URL, System.getProperty("kb.it.user", "kb"),
                System.getProperty("kb.it.password", "kb"));
    }

    /** 条件更新：同一天第二次「抢占」必须影响 0 行（登录奖励防双领的基础） */
    @Test
    void conditionalUpdate_onlyFirstOneWins() throws Exception {
        try (Connection c = connect()) {
            c.createStatement().execute("DROP TABLE IF EXISTS it_login_reward");
            c.createStatement().execute("CREATE TABLE it_login_reward (id BIGINT PRIMARY KEY, last_day DATE)");
            c.createStatement().execute("INSERT INTO it_login_reward VALUES (1, NULL)");

            assertEquals(1, claim(c, "2026-11-21"), "首次应影响 1 行");
            assertEquals(0, claim(c, "2026-11-21"), "同一天第二次必须影响 0 行");
            assertEquals(1, claim(c, "2026-11-22"), "次日可再次抢占");

            c.createStatement().execute("DROP TABLE it_login_reward");
        }
    }

    /** 唯一约束：同一 (user, chapter) 第二条插入必须失败（进度表防重复行） */
    @Test
    void uniqueConstraint_blocksDuplicateProgress() throws Exception {
        try (Connection c = connect()) {
            c.createStatement().execute("DROP TABLE IF EXISTS it_progress");
            c.createStatement().execute(
                    "CREATE TABLE it_progress (user_id BIGINT, chapter_id BIGINT, finished BOOLEAN DEFAULT FALSE,"
                            + " CONSTRAINT it_progress_uniq UNIQUE (user_id, chapter_id))");
            c.createStatement().execute("INSERT INTO it_progress (user_id, chapter_id) VALUES (1, 2)");

            boolean duplicated;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO it_progress (user_id, chapter_id) VALUES (1, 2)")) {
                ps.executeUpdate();
                duplicated = true;
            } catch (Exception e) {
                duplicated = false;
            }
            assertTrue(!duplicated, "唯一约束应拦住重复行");

            c.createStatement().execute("DROP TABLE it_progress");
        }
    }

    private int claim(Connection c, String day) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE it_login_reward SET last_day = ? WHERE id = 1 AND (last_day IS NULL OR last_day < ?)")) {
            ps.setObject(1, java.time.LocalDate.parse(day));
            ps.setObject(2, java.time.LocalDate.parse(day));
            return ps.executeUpdate();
        }
    }
}
