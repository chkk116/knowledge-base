/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import java.util.Set;

/**
 * 细粒度权限码白名单（单一事实来源）。
 * 旧实现散落在 AdminService.PERM_WHITELIST，现集中于此，供 AdminService / OrgService 共用，
 * 新增权限码时只改这一处即可。
 */
public final class Perm {

    public static final Set<String> WHITELIST = Set.of(
            "USER_MANAGE", "DOC_REVIEW", "POST_MANAGE", "STATS_VIEW", "BLACKLIST_MANAGE", "TAXONOMY_MANAGE",
            "ANNOUNCEMENT_MANAGE", "COURSE_MANAGE", "EXAM_MANAGE",
            "SHARE_CREATE", "SHARE_VIEW", "SHARE_DELETE", "SHARE_CAPACITY", "CORE_MAINTAIN",
            "DOC_EXPORT", "BANNED_WORDS_MANAGE", "ASSET_MANAGE", "AVATAR_REVIEW"
    );

    private Perm() {
    }

    /** 校验单个码是否在白名单内 */
    public static boolean valid(String code) {
        return code != null && WHITELIST.contains(code.trim().toUpperCase());
    }
}
