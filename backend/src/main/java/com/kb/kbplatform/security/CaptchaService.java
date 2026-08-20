package com.kb.kbplatform.security;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录滑动拼图验证码（自托管、免注册）：
 *  - 后端只负责生成背景图 + 签发一次性票据（token），正确性由前端 slide-verify 组件内部判定。
 *  - token 生命周期：生成时为 PENDING；前端滑动成功回调 /captcha/slide 后标记为 PASSED；
 *    登录时 consume() 一次性消费，且必须已 PASSED + 未过期，否则拒绝。
 *  - 内存存储，5 分钟过期，校验/消费后即失效，顺带清理过期项避免堆积。
 *  - 返回 base64 PNG 背景图，前端直接作为 slide-verify 的 imgUrl，无外网依赖。
 *
 * 说明：vue3-slide-verify 的拼图对齐为前端判定（无服务端密钥），整体属于「UX 闸门 + 一次性票据」
 * 的成熟免费方案，足以挡住绝大多数脚本化暴力登录；若要更强的安全性需接入极验等第三方行为验证码
 * 或在服务端实现权威拼图（服务端生成缺口与正确偏移并校验）。
 */
@Service
public class CaptchaService {

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMillis = 5 * 60 * 1000L;

    // W/H 需与前端 slide-verify 的 imgSize 一致，否则拼图缺口会错位
    static final int W = 320, H = 160;

    private static class Entry {
        final boolean passed;   // 是否已完成滑动验证
        final long expireAt;
        Entry(boolean passed, long expireAt) {
            this.passed = passed;
            this.expireAt = expireAt;
        }
    }

    /** 生成滑动拼图背景图（仅作 UI 背景），返回 { token, image(base64 PNG) } */
    public Map<String, String> generate() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(en -> en.getValue().expireAt < now); // 顺带清理过期

        String token = UUID.randomUUID().toString();
        store.put(token, new Entry(false, now + ttlMillis));
        return Map.of("token", token, "image", "data:image/png;base64," + renderBg());
    }

    /** 滑动成功后由前端回调：将 token 标记为已通过（需未过期） */
    public boolean markPassed(String token) {
        if (token == null) return false;
        Entry e = store.get(token);
        if (e == null || System.currentTimeMillis() > e.expireAt) return false;
        store.put(token, new Entry(true, e.expireAt));
        return true;
    }

    /** 登录时消费票据：必须存在、未过期、且已完成滑动；一次性 */
    public boolean consume(String token) {
        if (token == null) return false;
        Entry e = store.remove(token);
        if (e == null) return false;
        if (System.currentTimeMillis() > e.expireAt) return false;
        return e.passed;
    }

    /** 生成一张 WxH 的渐变背景图（自托管，无外网依赖），作为拼图底图 */
    private String renderBg() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 随机渐变背景
        Color c1 = new Color(70 + random.nextInt(120), 90 + random.nextInt(120), 180 + random.nextInt(75));
        Color c2 = new Color(180 + random.nextInt(75), 110 + random.nextInt(120), 140 + random.nextInt(115));
        g.setPaint(new GradientPaint(0, 0, c1, W, H, c2));
        g.fillRect(0, 0, W, H);
        // 随机圆形点缀，增加底图纹理（不影响拼图判定，仅视觉）
        for (int i = 0; i < 7; i++) {
            g.setColor(new Color(random.nextInt(255), random.nextInt(255), random.nextInt(255), 110));
            int r = 12 + random.nextInt(46);
            g.fillOval(random.nextInt(W), random.nextInt(H), r, r);
        }
        g.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("验证码背景图生成失败", e);
        }
    }
}
