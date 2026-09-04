package com.kb.kbplatform.security;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录滑动拼图验证码（自托管、免注册、服务端权威判定）：
 *  - 服务端从「风景图素材库」随机选一张图做底图，并在服务端选定的随机位置 correctX/Y 挖缺口，
 *    把 correctX 存在内存票据里（绝不下发给前端）——前端只拿到「带极淡槽位提示的背景图 bg」和
 *    「拼块图 piece」，负责展示与拖拽，松手时把用户偏移 userX 回传。
 *  - 拼块形状每次随机（圆角矩形/圆/五角星/六边形/三角形/方形），视觉上就是用户原来的「各种形状拼图」。
 *  - 服务端 markPassed(token, userX) 校验 |userX - correctX| <= THRESHOLD 才标记通过；
 *    一次对齐失败即作废该票据，防止脚本按偏移逐步穷举。
 *  - 安全要点：缺口位置由服务端决定且不下发，机器人无法从页面读到正确坐标，必须真的把拼块对齐到
 *    服务端选定的随机位置。整体属于「UX 闸门 + 一次性票据 + 服务端对齐校验」，足以挡住绝大多数
 *    脚本化暴力登录；真要更强可接极验/腾讯云验证码等第三方行为验证码。
 */
@Service
public class CaptchaService {

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMillis = 5 * 60 * 1000L;

    // 抗爆破：单票据最多验证次数（超次即作废，强制重新拉取，使「逐像素穷举」无法在单票据内完成）
    private static final int MAX_TRIES_PER_TOKEN = 5;
    // 抗爆破：同一 IP 连续验证失败达到阈值后锁定一段时间，挡掉脚本高频爆破
    private static final int IP_FAIL_LIMIT = 8;          // 窗口内允许的最大失败次数
    private static final long IP_LOCK_MILLIS = 10 * 60 * 1000L;  // 锁定 10 分钟
    // IP → 失败计数（failCount 达到上限后 lockUntil 生效）
    private final ConcurrentHashMap<String, IpStat> ipStats = new ConcurrentHashMap<>();

    // 画布与拼块尺寸（需与前端展示尺寸一致）
    static final int W = 320, H = 160;
    static final int PIECE_W = 45, PIECE_H = 45;
    private static final int RADIUS = 6;          // 圆角半径
    private static final int THRESHOLD = 6;        // 对齐容差（像素）
    private static final int SHAPE_COUNT = 6;      // 拼块形状种类数

    /** 后端资源下的风景图素材（打包进 jar，零外网依赖） */
    private final List<BufferedImage> scenes = new ArrayList<>();

    private static class Entry {
        final boolean passed;   // 是否已完成滑动验证
        final long expireAt;
        final int correctX;     // 缺口左上角 X（服务端秘密，不下发）
        int tries;              // 已尝试次数（抗爆破）
        Entry(boolean passed, long expireAt, int correctX) {
            this.passed = passed;
            this.expireAt = expireAt;
            this.correctX = correctX;
            this.tries = 0;
        }
    }

    /** 单 IP 验证失败统计（抗爆破） */
    private static class IpStat {
        int failCount;          // 窗口内失败次数
        long windowStart;       // 滑动窗口起点
        long lockUntil;         // 锁定截止时间（0=未锁定）
        IpStat() { this.windowStart = System.currentTimeMillis(); }
    }

    /** 启动时把风景图素材一次性载入内存；若素材缺失则退回纯色纹理底图 */
    @jakarta.annotation.PostConstruct
    public void init() {
        for (int i = 1; i <= 16; i++) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("captcha/scenic" + i + ".jpg")) {
                if (in == null) continue;
                BufferedImage img = ImageIO.read(in);
                if (img != null) scenes.add(img);
            } catch (Exception ignore) {
                // 单张损坏不影响整体，跳过
            }
        }
    }

    /**
     * 生成滑动拼图：返回 { token, bg(base64 PNG 风景底图+极淡槽位提示), piece(base64 PNG 拼块),
     * w, h, pieceW, pieceH, pieceY }。correctX 仅存于服务端票据，不出现在返回体中。
     */
    public Map<String, Object> generate() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(en -> en.getValue().expireAt < now); // 顺带清理过期

        // correctX ∈ [PIECE_W+12, W-PIECE_W-12]，correctY ∈ [12, H-PIECE_H-12]
        int cx = PIECE_W + 12 + random.nextInt(W - 2 * PIECE_W - 24);
        int cy = 12 + random.nextInt(H - PIECE_H - 24);
        int shapeType = random.nextInt(SHAPE_COUNT);

        // 底图：优先用随机风景图，否则退回纹理渐变
        BufferedImage base;
        if (!scenes.isEmpty()) {
            BufferedImage src = scenes.get(random.nextInt(scenes.size()));
            base = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D bg = base.createGraphics();
            bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            bg.drawImage(src, 0, 0, W, H, null); // 拉伸铺满画布
            bg.dispose();
        } else {
            base = buildBackground();
        }

        // 背景图：底图 + 清晰可见的“凹槽”提示（形状随机）。人类一眼能看到缺块在哪、对位靠纹理
        // 连续性；正确坐标仍只存服务端，机器人无法从接口读到。视觉与安全解耦：看得清 ≠ 能被脚本读取。
        BufferedImage bg = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(base, 0, 0, null);
        Shape s = makeShape(shapeType, cx, cy);
        // 凹槽阴影：半透明深色填充，像“被挖掉一块”
        g.setColor(rgba(0, 0, 0, 60));
        g.fill(s);
        // 清晰描边，边界一眼可辨
        g.setColor(rgba(0, 0, 0, 165));
        g.setStroke(new BasicStroke(2f));
        g.draw(s);
        // 内侧高光，增加立体感（不影响人类辨识）
        g.setColor(rgba(255, 255, 255, 70));
        g.setStroke(new BasicStroke(1f));
        g.draw(makeShape(shapeType, cx + 1, cy + 1));
        g.dispose();

        // 拼块图：透明底，按随机形状裁切出底图对应切片 + 贴边白边（白边裁在形状内，不溢出成浮空环）
        BufferedImage piece = new BufferedImage(PIECE_W, PIECE_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = piece.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape ps = makeShape(shapeType, 0, 0); // 拼块本地坐标
        pg.clip(ps);
        pg.drawImage(base, -cx, -cy, null);    // 把底图相应切片移入拼块
        // 白边：保持 clip，只描内侧半边，避免描边溢出到形状外形成“浮空白环”
        pg.setStroke(new BasicStroke(2f));
        pg.setColor(rgba(255, 255, 255, 235));
        pg.draw(ps);
        pg.dispose();

        String token = UUID.randomUUID().toString();
        store.put(token, new Entry(false, now + ttlMillis, cx));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("bg", "data:image/png;base64," + b64(bg));
        m.put("piece", "data:image/png;base64," + b64(piece));
        m.put("w", W);
        m.put("h", H);
        m.put("pieceW", PIECE_W);
        m.put("pieceH", PIECE_H);
        m.put("pieceY", cy);
        return m;
    }

    /** 滑动成功后由前端回调：将 token 标记为已通过（需未过期、偏移对齐、且拖拽轨迹像人类）；
     *  失败即作废防穷举；并对单票据尝试次数 + 单 IP 失败频率做限流，挡掉脚本化爆破。
     *  @param clientIp 调用方 IP（来自 HttpServletRequest），用于 IP 级限流
     *  @return true=通过，false=未通过（含被锁定/超次/轨迹非人类） */
    public boolean markPassed(String token, Integer userX, List<Map<String, Object>> trajectory, String clientIp) {
        // 1) IP 级锁定：连续失败过多则窗口内拒绝，迫使脚本等待
        if (clientIp != null && isIpLocked(clientIp)) return false;

        if (token == null || userX == null) return false;
        Entry e = store.get(token);
        if (e == null) return false;
        if (System.currentTimeMillis() > e.expireAt) { store.remove(token); return false; }

        // 2) 单票据尝试次数上限：超过即作废，脚本无法在单票据内逐像素穷举（correctX 范围约 200 个值）
        e.tries++;
        if (e.tries > MAX_TRIES_PER_TOKEN) {
            store.remove(token);
            registerIpFail(clientIp);
            return false;
        }

        // 3) 轨迹必填且需像人类：缺轨迹 / 时长过短视为脚本，直接拒绝（不再仅作“向后兼容放行”）
        if (!trajectoryLooksHuman(trajectory)) {
            registerIpFail(clientIp);
            return false;
        }

        if (Math.abs(userX - e.correctX) > THRESHOLD) {
            registerIpFail(clientIp);  // 对齐失败计入 IP 失败，逼近锁定时长
            store.remove(token);       // 作废票据，必须重新拉取
            return false;
        }

        store.put(token, new Entry(true, e.expireAt, e.correctX));
        resetIpFail(clientIp);         // 成功则清零该 IP 失败计数
        return true;
    }

    /** 该 IP 当前是否处于锁定状态（供 AuthController 在验证前给出明确提示） */
    public boolean isBlocked(String ip) {
        return ip != null && isIpLocked(ip);
    }

    /** 是否环回/本地地址（127.0.0.1、::1、0:0:0:0:0:0:0:1 等）：本地开发所有请求共享该 IP，
     *  不参与 IP 锁定，否则一次自测就会把自己（及同机其他本地测试者）锁 10 分钟。 */
    private boolean isLoopback(String ip) {
        if (ip == null) return false;
        String s = ip.replaceAll("[\\[\\]]", "").trim();
        return s.equals("127.0.0.1") || s.equals("::1")
            || s.equals("0:0:0:0:0:0:0:1") || s.startsWith("127.");
    }

    /** 该 IP 当前是否处于锁定状态 */
    private boolean isIpLocked(String ip) {
        if (ip == null || isLoopback(ip)) return false;  // 环回地址（本地开发共享 127.0.0.1）不锁定，避免自锁/锁全站
        IpStat s = ipStats.get(ip);
        if (s == null) return false;
        return s.lockUntil > System.currentTimeMillis();
    }

    /** 记录一次失败：滑动窗口内失败次数 +1，达到上限则锁定 IP_LOCK_MILLIS */
    private void registerIpFail(String ip) {
        if (ip == null || isLoopback(ip)) return;
        long now = System.currentTimeMillis();
        IpStat s = ipStats.computeIfAbsent(ip, k -> new IpStat());
        // 窗口过期则重置计数
        if (now - s.windowStart > IP_LOCK_MILLIS) {
            s.failCount = 0;
            s.windowStart = now;
            s.lockUntil = 0;
        }
        s.failCount++;
        if (s.failCount >= IP_FAIL_LIMIT) {
            s.lockUntil = now + IP_LOCK_MILLIS;
        }
    }

    /** 验证成功：清零该 IP 失败计数（避免误伤正常用户） */
    private void resetIpFail(String ip) {
        if (ip == null || isLoopback(ip)) return;
        IpStat s = ipStats.get(ip);
        if (s != null) { s.failCount = 0; s.lockUntil = 0; s.windowStart = System.currentTimeMillis(); }
    }

    /** 轻量轨迹校验：人类拖拽必有时长且至少 3 个采样点；缺轨迹/过短视为脚本，直接拒绝。
     *  返回 true=像人类/放行；false=瞬时提交（疑似脚本）。 */
    private boolean trajectoryLooksHuman(List<Map<String, Object>> traj) {
        if (traj == null || traj.size() < 3) return false;
        try {
            long t0 = ((Number) traj.get(0).get("t")).longValue();
            long t1 = ((Number) traj.get(traj.size() - 1).get("t")).longValue();
            return (t1 - t0) >= 120; // 人类一次拖拽远超 120ms；瞬时 POST 直接判非人类
        } catch (Exception e) {
            return false;
        }
    }

    /** 登录时消费票据：必须存在、未过期、且已完成滑动；一次性 */
    public boolean consume(String token) {
        if (token == null) return false;
        Entry e = store.remove(token);
        if (e == null) return false;
        if (System.currentTimeMillis() > e.expireAt) return false;
        return e.passed;
    }

    /** 生成一张带丰富纹理的渐变背景图（兜底底图，无素材时使用） */
    private BufferedImage buildBackground() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color c1 = rgba(70 + random.nextInt(120), 90 + random.nextInt(120), 180 + random.nextInt(75), 255);
        Color c2 = rgba(180 + random.nextInt(75), 110 + random.nextInt(120), 140 + random.nextInt(115), 255);
        g.setPaint(new GradientPaint(0, 0, c1, W, H, c2));
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < 12; i++) {
            g.setColor(rgba(random.nextInt(255), random.nextInt(255), random.nextInt(255), 80 + random.nextInt(60)));
            int r = 18 + random.nextInt(54);
            g.fillOval(random.nextInt(W), random.nextInt(H), r, r);
        }
        for (int i = 0; i < 16; i++) {
            g.setColor(rgba(random.nextInt(255), random.nextInt(255), random.nextInt(255), 60 + random.nextInt(60)));
            g.setStroke(new BasicStroke(1 + random.nextInt(3)));
            int x1 = random.nextInt(W), y1 = random.nextInt(H);
            g.drawLine(x1, y1, x1 + 20 + random.nextInt(60) - 30, y1 + 20 + random.nextInt(60) - 30);
        }
        g.setColor(rgba(255, 255, 255, 80));
        for (int i = 0; i < 70; i++) {
            g.fillRect(random.nextInt(W), random.nextInt(H), 2, 2);
        }
        g.dispose();
        return img;
    }

    /** 按类型生成拼块形状（Top-Left 落在 (x,y)，整体落入 PIECE_W×PIECE_H 框内） */
    private Shape makeShape(int type, int x, int y) {
        switch (type) {
            case 0: // 圆角矩形
                return new RoundRectangle2D.Double(x, y, PIECE_W, PIECE_H, 10, 10);
            case 1: // 圆形
                return new Ellipse2D.Double(x + 3, y + 3, PIECE_W - 6, PIECE_H - 6);
            case 2: // 五角星
                return star(x + PIECE_W / 2.0, y + PIECE_H / 2.0, PIECE_W / 2.0 - 2, (PIECE_W / 2.0 - 2) * 0.45, 5);
            case 3: // 六边形
                return polygon(6, x + PIECE_W / 2.0, y + PIECE_H / 2.0, PIECE_W / 2.0 - 2, -Math.PI / 2);
            case 4: // 三角形
                return polygon(3, x + PIECE_W / 2.0, y + PIECE_H / 2.0, PIECE_W / 2.0 - 2, -Math.PI / 2);
            default: // 方形
                return new Rectangle2D.Double(x + 5, y + 5, PIECE_W - 10, PIECE_H - 10);
        }
    }

    /** 生成正 N 边形 Path2D（rot 为首个顶点角度，弧度） */
    private Shape polygon(int n, double cx, double cy, double r, double rot) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < n; i++) {
            double a = rot + i * 2 * Math.PI / n;
            double px = cx + r * Math.cos(a);
            double py = cy + r * Math.sin(a);
            if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.closePath();
        return p;
    }

    /** 生成 N 角星 Path2D（外/内半径交替） */
    private Shape star(double cx, double cy, double outer, double inner, int points) {
        Path2D p = new Path2D.Double();
        int total = points * 2;
        for (int i = 0; i < total; i++) {
            double r = (i % 2 == 0) ? outer : inner;
            double a = -Math.PI / 2 + i * Math.PI / points;
            double px = cx + r * Math.cos(a);
            double py = cy + r * Math.sin(a);
            if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.closePath();
        return p;
    }

    /** 将任意分量安全映射到 [0,255]，杜绝 java.awt.Color 因分量越界抛异常 */
    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 唯一构造颜色的入口：每个分量先 clamp 再交给 Color，从根上杜绝越界异常 */
    private static Color rgba(int r, int g, int b, int a) {
        return new Color(clamp255(r), clamp255(g), clamp255(b), clamp255(a));
    }

    private String b64(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("验证码图片生成失败", e);
        }
    }
}
