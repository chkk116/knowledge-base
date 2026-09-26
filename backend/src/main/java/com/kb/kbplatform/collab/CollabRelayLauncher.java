/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.collab;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把共享文档实时协同中继（collab-relay/index.js，Node/Hocuspocus 进程）托管进 Spring Boot：
 *  - 应用启动后自动拉起，关闭时一并销毁，无需手动 `npm start`；
 *  - 从 application.yml 注入 KB_TOKEN_SECRET / 数据库 / 端口，保证与后端使用同一套密钥与数据源；
 *  - node_modules 缺失时按配置自动 `npm install` 一次；
 *  - 中继进程崩溃不影响主应用（仅协同编辑不可用），并在日志中告警。
 *
 * 前提：部署机已安装 Node（relay 需 Node 运行时）。开发机（IDEA）自带 Node。
 */
@Component
public class CollabRelayLauncher implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CollabRelayLauncher.class);

    @Value("${kb.collab.enabled:true}")
    private boolean enabled;

    @Value("${kb.collab.port:1234}")
    private int port;

    /** 中继目录（含 index.js / package.json）；默认相对后端模块为 ../collab-relay */
    @Value("${kb.collab.relay-dir:../collab-relay}")
    private String relayDirRaw;

    @Value("${kb.collab.node-path:node}")
    private String nodePath;

    @Value("${kb.collab.auto-install:true}")
    private boolean autoInstall;

    // C: 协同文档快照保存间隔（毫秒），默认 5s；越小丢失窗口越小
    @Value("${kb.collab.save-interval:5000}")
    private int saveInterval;

    @org.springframework.beans.factory.annotation.Autowired
    private com.kb.kbplatform.security.TokenSecretProvider tokenSecretProvider;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username:kb}")
    private String dbUser;

    @Value("${spring.datasource.password:kb}")
    private String dbPass;

    private Process relayProcess;
    private volatile boolean stopping = false;

    /** 中继异常退出后的自动重启上限（防 crash loop 刷日志）；间隔 5s */
    private static final int MAX_RESTARTS = 5;
    private static final long RESTART_DELAY_MS = 5000;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[relay] 已禁用（kb.collab.enabled=false），协同中继不会启动");
            return;
        }
        // 后台启动，避免 npm install 拖慢应用启动
        CompletableFuture.runAsync(this::supervise);
    }

    /** 中继守护循环：启动 → 监视退出 → 非主动关闭则延迟重启（带上限）；端口已被占则复用已有中继 */
    private void supervise() {
        File relayDir = resolveRelayDir();
        if (relayDir == null || !relayDir.isDirectory()) {
            log.warn("[relay] 中继目录不存在：{}，协同编辑将不可用（请在 kb.collab.relay-dir 配置正确路径）", relayDirRaw);
            return;
        }
        File indexJs = new File(relayDir, "index.js");
        if (!indexJs.exists()) {
            log.warn("[relay] 未找到 {}/index.js，协同编辑将不可用", relayDir.getAbsolutePath());
            return;
        }

        // 依赖缺失则尝试安装一次
        File nodeModules = new File(relayDir, "node_modules");
        if (!nodeModules.exists() && autoInstall) {
            log.info("[relay] 未检测到 node_modules，正在自动 npm install（目录 {}）…", relayDir.getAbsolutePath());
            String[] npm = npmCommand();
            if (!runSimple(new String[]{npm[0], "install"}, relayDir)) {
                log.warn("[relay] npm install 失败，尝试直接启动（若缺依赖则会失败）");
            }
        }

        int restarts = 0;
        while (!stopping) {
            // 预检端口：已被监听 = 大概率已有中继在跑（如强杀后端留下的孤儿进程）。
            // 前端只连 ws://host:1234，谁拉起的中继无所谓——直接复用，避免 EADDRINUSE 反复崩溃。
            // （本地密钥已持久化到用户目录，复用旧中继的令牌校验依然有效。）
            if (portOccupied()) {
                log.info("[relay] 端口 {} 已被监听，判定已有协同中继在运行——直接复用，不再自启", port);
                return;
            }
            Process p = startRelay(relayDir);
            if (p == null) {
                if (stopping) return;
            } else {
                try {
                    int code = p.waitFor();   // 同步监视退出
                    if (stopping) return;
                    log.error("[relay] 协同中继进程异常退出（exit={}）", code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (++restarts > MAX_RESTARTS) {
                log.error("[relay] 已连续 {} 次启动/重启失败，放弃自动重启（协同编辑不可用）。排查环境后重启应用即可恢复", MAX_RESTARTS);
                return;
            }
            log.warn("[relay] {}ms 后自动重启中继（第 {}/{} 次）…", RESTART_DELAY_MS, restarts, MAX_RESTARTS);
            try {
                Thread.sleep(RESTART_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 拉起中继进程并桥接日志；失败返回 null（日志已打） */
    private Process startRelay(File relayDir) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(nodePath);
            cmd.add("index.js");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(relayDir);
            pb.redirectErrorStream(true);
            // 注入与后端一致的密钥与数据源，环境变量的键与 collab-relay/index.js 对齐
            pb.environment().put("COLLAB_PORT", String.valueOf(port));
            pb.environment().put("COLLAB_SAVE_INTERVAL", String.valueOf(saveInterval));
            pb.environment().put("KB_TOKEN_SECRET", tokenSecretProvider.get());
            DbConn db = parseDbUrl();
            pb.environment().put("COLLAB_DB_HOST", db.host);
            pb.environment().put("COLLAB_DB_PORT", String.valueOf(db.port));
            pb.environment().put("COLLAB_DB_USER", dbUser);
            pb.environment().put("COLLAB_DB_PASS", dbPass);
            pb.environment().put("COLLAB_DB_NAME", db.name);

            log.info("[relay] 正在启动协同中继：{} -> ws://0.0.0.0:{}（目录 {}）", nodePath, port, relayDir.getAbsolutePath());
            relayProcess = pb.start();
            // 将中继日志桥接到应用日志，便于在后端控制台统一观察
            bridgeOutput(relayProcess);
            return relayProcess;
        } catch (Exception e) {
            log.error("[relay] 启动失败：{}，协同编辑不可用", e.getMessage());
            return null;
        }
    }

    /** 端口是否已被监听（bind 探测，不实际连接）：被占 = 已有中继或其它服务在用 */
    private boolean portOccupied() {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** 把子进程标准输出（含错误流）逐行转发到应用日志 */
    private void bridgeOutput(Process p) {
        CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("[relay-out] {}", line);
                }
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 解析 npm 可执行命令。Windows 上 npm 是 npm.cmd 批处理，Java 的 ProcessBuilder
     * 不做 PATHEXT 解析（直接写 "npm" 会 CreateProcess error=2），须显式带扩展名：
     * 优先取 node 同目录（node-path 为绝对路径时最可靠），否则依赖 PATH 中的 npm.cmd。
     */
    private String[] npmCommand() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!win) return new String[]{"npm"};
        File nodeFile = new File(nodePath);
        if (nodeFile.isAbsolute()) {
            File npmCmd = new File(nodeFile.getParentFile(), "npm.cmd");
            if (npmCmd.isFile()) return new String[]{npmCmd.getAbsolutePath()};
        }
        return new String[]{"npm.cmd"};
    }

    /** 简单执行命令（用于 npm install），阻塞等待完成，返回是否成功 */
    private boolean runSimple(String[] cmd, File dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) log.info("[relay-npm] {}", line);
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            log.warn("[relay] 执行 {} 异常：{}", String.join(" ", cmd), e.getMessage());
            return false;
        }
    }

    private File resolveRelayDir() {
        File f = new File(relayDirRaw);
        if (f.isAbsolute()) return f;
        // 相对路径按工作目录歧义探测：IDEA 运行配置的工作目录可能是 backend/（模块目录）
        // 也可能是项目根（IDE 默认），依次尝试候选，第一个存在的生效
        String cwd = System.getProperty("user.dir");
        File[] candidates = {
                new File(cwd, relayDirRaw),                       // backend/ 或仓库根直接拼
                new File(cwd, "collab-relay"),                    // 仓库根启动时
                new File(cwd, "backend/collab-relay")             // 兜底
        };
        for (File c : candidates) {
            if (c.isDirectory()) return c;
        }
        return candidates[0];   // 都不存在时返回首选，让上层日志提示路径
    }

    /** 解析 jdbc:mysql://host:port/db?... 或 jdbc:postgresql://host:port/db?... */
    private DbConn parseDbUrl() {
        DbConn c = new DbConn("localhost", 5432, "kb");
        if (dbUrl != null) {
            Matcher m = Pattern.compile("jdbc:(?:mysql|postgresql|pgsql)://([^:/?]+)(?::(\\d+))?/([^?]+)").matcher(dbUrl);
            if (m.find()) {
                c.host = m.group(1);
                if (m.group(2) != null) c.port = Integer.parseInt(m.group(2));
                c.name = m.group(3);
            }
        }
        return c;
    }

    private static class DbConn {
        String host; int port; String name;
        DbConn(String h, int p, String n) { host = h; port = p; name = n; }
    }

    @PreDestroy
    public void destroy() {
        stopping = true;
        if (relayProcess != null && relayProcess.isAlive()) {
            log.info("[relay] 正在关闭协同中继…");
            relayProcess.destroy();           // 触发 SIGTERM，relay 内部会落库后退出
            try {
                if (!relayProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    relayProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                relayProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
