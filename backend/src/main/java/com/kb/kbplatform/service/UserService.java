/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Follow;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.FavoriteRepository;
import com.kb.kbplatform.repository.FollowRepository;
import com.kb.kbplatform.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserService {

    private final UserRepository userRepo;
    private final DocumentRepository docRepo;
    private final FavoriteRepository favRepo;
    private final FollowRepository followRepo;

    public UserService(UserRepository userRepo, DocumentRepository docRepo, FavoriteRepository favRepo, FollowRepository followRepo) {
        this.userRepo = userRepo;
        this.docRepo = docRepo;
        this.favRepo = favRepo;
        this.followRepo = followRepo;
    }

    /** 作者小卡片：原创数 / 获赞数 / 被收藏数 / 粉丝 / 关注 / 码龄 / 当前访客是否已关注 */
    @Cacheable(cacheNames = "userCard", key = "#id + '_' + #viewerId", unless = "#result == null")
    public Map<String, Object> getCard(Long id, Long viewerId) {
        User u = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        // createdAt 可能为 null（库里早期创建的用户），空指针保护，避免卡片接口 500
        int codingAge = (u.getCreatedAt() == null) ? 0
                : Math.max(0, Year.now().getValue() - u.getCreatedAt().getYear());
        long original = docRepo.countByOwnerIdAndStatus(id, "PUBLISHED");
        long likeCount = docRepo.sumLikesByOwner(id);
        long favCount = favRepo.countFavByOwner(id);
        long fans = followRepo.countByFollowingId(id);
        long following = followRepo.countByFollowerId(id);
        boolean isFollowing = viewerId != null && followRepo.existsByFollowerIdAndFollowingId(viewerId, id);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        // 不下发 username（登录名）：卡片是公开接口，对外身份只有 displayName
        // （外部审查 2026-10-11；前端展示均已 displayName 优先）
        m.put("displayName", u.getDisplayName());
        m.put("avatar", u.getAvatar());
        m.put("bio", u.getBio());
        m.put("codingAge", codingAge);
        m.put("original", original);
        m.put("likes", likeCount);
        m.put("favorites", favCount);
        m.put("fans", fans);
        m.put("following", following);
        m.put("points", u.getPoints());
        m.put("displayBadge", u.getDisplayBadge());
        m.put("wornBadges", u.getWornList());
        m.put("isFollowing", isFollowing);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "userCard", allEntries = true)
    public boolean follow(Long viewerId, Long targetId) {
        if (viewerId.equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能关注自己");
        }
        userRepo.findById(targetId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!followRepo.existsByFollowerIdAndFollowingId(viewerId, targetId)) {
            Follow f = new Follow();
            f.setFollowerId(viewerId);
            f.setFollowingId(targetId);
            followRepo.save(f);
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "userCard", allEntries = true)
    public boolean unfollow(Long viewerId, Long targetId) {
        followRepo.deleteByFollowerIdAndFollowingId(viewerId, targetId);
        return true;
    }

    /** 我关注的人（公开，分页，按关注时间倒序） */
    public List<Map<String, Object>> listFollowing(Long id, int page, int size) {
        Page<Follow> rel = followRepo.findByFollowerId(id,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return toSummaries(rel.getContent().stream().map(Follow::getFollowingId).toList());
    }

    /** 我的粉丝（公开，分页，按关注时间倒序） */
    public List<Map<String, Object>> listFollowers(Long id, int page, int size) {
        Page<Follow> rel = followRepo.findByFollowingId(id,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return toSummaries(rel.getContent().stream().map(Follow::getFollowerId).toList());
    }

    /**
     * 批量把用户精简为卡片列表所需字段（外部审查 2026-10-13）：
     *  - 一次 findAllById 批量查询，替代原来「每个关注关系一次 findById」的 N+1；
     *  - 不再下发 username（登录名）：粉丝/关注列表是公开接口，暴露登录名可助密码爆破，
     *    对外身份统一只用 displayName（与作者卡片 getCard 口径一致）。
     */
    private List<Map<String, Object>> toSummaries(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Map<Long, User> byId = new HashMap<>();
        for (User u : userRepo.findAllById(ids)) byId.put(u.getId(), u);
        List<Map<String, Object>> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            User u = byId.get(id);
            if (u == null) continue;   // 用户已注销：静默跳过
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("displayName", u.getDisplayName());
            m.put("avatar", u.getAvatar());
            m.put("bio", u.getBio());
            out.add(m);
        }
        return out;
    }
}
