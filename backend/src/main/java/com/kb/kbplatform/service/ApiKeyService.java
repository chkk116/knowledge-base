/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.ApiKey;
import com.kb.kbplatform.repository.ApiKeyRepository;
import com.kb.kbplatform.security.IpAllowList;
import com.kb.kbplatform.security.TokenClaims;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 个人 API 密钥：创建 / 列表 / 吊销 / 校验。
 *
 * <p>调用方带 {@code X-API-Key}，这里把它换算成「该账号的声明」，权限再与密钥作用域取交集。
 * 明文密钥只在创建时返回一次，库里只有 SHA-256。
 */
@Service
public class ApiKeyService {

    /** 每个账号最多保留多少把（含已吊销的，吊销的也算配额，避免无限堆积） */
    private static final int MAX_KEYS_PER_USER = 20;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijkmnopqrstuvwxyz23456789";   // 去掉了易混的 l/1/o/0

    private final ApiKeyRepository repo;

    public ApiKeyService(ApiKeyRepository repo) {
        this.repo = repo;
    }

    /**
     * 新建密钥。
     *
     * @return 明文密钥（形如 kb_9f3a2c7d5e1b...），只此一次
     */
    @Transactional
    public String create(Long userId, String name, List<String> scopes, Integer expiresInDays, List<String> allowedIps) {
        // 已吊销的也计入配额：只数「有效」的话，创建→吊销循环可以无限攒记录
        if (repo.countByUserId(userId) >= MAX_KEYS_PER_USER) {
            throw new BizException("密钥数量已达上限（" + MAX_KEYS_PER_USER + " 个），请先吊销不用的");
        }
        String plain = "kb_" + randomToken(32);
        ApiKey k = new ApiKey();
        k.setUserId(userId);
        k.setName(sanitizeName(name));
        k.setKeyPrefix(plain.substring(0, Math.min(11, plain.length())));
        k.setKeyHash(sha256(plain));
        k.setScopes(joinScopes(scopes));
        // 规范化和校验放在这里：写进库的必须是能用的规则，否则用户以为配了白名单其实没生效
        try {
            k.setAllowedIps(IpAllowList.normalize(allowedIps));
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        }
        if (expiresInDays != null) {
            if (expiresInDays <= 0) throw new BizException("有效期必须大于 0 天");
            // 上限 365 天：传个 9999 不报错却被静默截断，用户会以为自己设了更长期限
            if (expiresInDays > 365) throw new BizException("有效期最长 365 天");
            k.setExpiresAt(LocalDateTime.now().plusDays(expiresInDays));
        }
        repo.save(k);
        return plain;
    }

    public List<ApiKey> list(Long userId) {
        return repo.findByUserIdOrderByIdDesc(userId);
    }

    @Transactional
    public void revoke(Long userId, Long id) {
        ApiKey k = repo.findById(id).orElseThrow(() -> new BizException("密钥不存在"));
        if (!k.getUserId().equals(userId)) throw new BizException("无权操作该密钥");
        k.setRevoked(true);
        repo.save(k);
    }

    /** 校验明文密钥：未吊销、未过期才返回记录；顺带更新「最后使用时间」用于用户自查 */
    @Transactional
    public ApiKey match(String presented) {
        if (presented == null || presented.isBlank()) return null;
        ApiKey k = repo.findByKeyHashAndRevokedFalse(sha256(presented.trim())).orElse(null);
        if (k == null) return null;
        if (k.getExpiresAt() != null && k.getExpiresAt().isBefore(LocalDateTime.now())) return null;
        // 每个请求都写一次库没必要：5 分钟内只更新一次
        LocalDateTime last = k.getLastUsedAt();
        if (last == null || last.isBefore(LocalDateTime.now().minusMinutes(5))) {
            k.setLastUsedAt(LocalDateTime.now());
            repo.save(k);
        }
        return k;
    }

    /**
     * 把账号声明与密钥作用域合成最终声明。
     *
     * <p>作用域为空 = 与账号一致（管理员仍是管理员）；有作用域时一律降级成普通角色，
     * 只保留交集权限码——否则给管理员创建「只读」密钥等于没限制。
     */
    public static TokenClaims narrow(TokenClaims account, String scopesRaw) {
        String scopes = scopesRaw == null ? "" : scopesRaw.trim();
        if (scopes.isEmpty()) return account;
        Set<String> allow = new LinkedHashSet<>();
        for (String s : scopes.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) allow.add(t);
        }
        List<String> kept = new ArrayList<>();
        String acct = account.permissions() == null ? "" : account.permissions();
        // 管理员账号的 permissions 列是空的（建角色时就清空了），按字符串取交集会把勾选的作用域
        // 全部丢掉，最后发出去一把「能认证但什么都不能做」的废钥匙。ADMIN 视为拥有全部权限码。
        boolean all = "ADMIN".equals(account.role()) || "*".equals(acct);
        for (String s : allow) {
            // 只保留账号本来就有的权限码：密钥不能提权
            if (all || containsPerm(acct, s)) kept.add(s);
        }
        String role = "ADMIN".equals(account.role()) ? "USER" : account.role();
        return new TokenClaims(account.userId(), account.username(), role,
                String.join(",", kept), account.exp(), account.tokenVersion());
    }

    private static boolean containsPerm(String perms, String code) {
        for (String p : perms.split(",")) {
            if (p.trim().equals(code)) return true;
        }
        return false;
    }

    private static String sanitizeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = "未命名密钥";
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    private static String joinScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) return "";
        Set<String> set = new LinkedHashSet<>();
        for (String s : scopes) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return String.join(",", set);
    }

    private static String randomToken(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
