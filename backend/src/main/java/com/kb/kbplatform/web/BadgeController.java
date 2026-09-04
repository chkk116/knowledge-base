package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.BadgeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/badges")
public class BadgeController {

    private final BadgeService badgeService;
    private final UserRepository userRepo;

    public BadgeController(BadgeService badgeService, UserRepository userRepo) {
        this.badgeService = badgeService;
        this.userRepo = userRepo;
    }

    /** 徽章墙：当前登录用户的所有徽章定义 + 已获得状态 + 进度 */
    @GetMapping
    public Map<String, Object> wall(@RequestAttribute("kbUser") TokenClaims c) {
        User me = userRepo.findByUsername(c.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        return badgeService.wall(me.getId());
    }

    /** 某用户已获得的徽章列表（公开，用于主页 / 资料页展示） */
    @GetMapping("/users/{id}")
    public List<Map<String, Object>> userBadges(@PathVariable Long id) {
        return badgeService.earned(id);
    }

    /**
     * 佩戴 / 卸下徽章（最多同时佩戴 {@link User#MAX_WORN} 枚）。
     * - badgeId 为空串或 null：清空全部佩戴
     * - badgeId 已佩戴：再次点击则卸下
     * - badgeId 未佩戴：若未达上限则佩戴，否则 400
     * 只能佩戴「已获得」的徽章，否则 400；佩戴状态持久化到 User.displayBadge（逗号分隔）。
     */
    @PostMapping("/wear")
    @CacheEvict(cacheNames = "badgeWall", allEntries = true)
    public Map<String, Object> wear(@RequestAttribute("kbUser") TokenClaims c,
                                   @RequestBody Map<String, String> body) {
        String badgeId = body == null ? null : body.get("badgeId");
        User me = userRepo.findByUsername(c.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        List<String> worn = me.getWornList();
        if (badgeId == null || badgeId.isBlank()) {
            worn = new ArrayList<>();
        } else {
            final String id = badgeId.trim();
            boolean earned = badgeService.earned(me.getId()).stream()
                    .anyMatch(m -> id.equals(String.valueOf(m.get("id"))));
            if (!earned) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该徽章尚未获得，无法佩戴");
            }
            if (worn.contains(id)) {
                worn.remove(id);            // 再次点击 → 卸下
            } else {
                if (worn.size() >= User.MAX_WORN) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最多同时佩戴 " + User.MAX_WORN + " 枚勋章");
                }
                worn.add(id);
            }
        }
        me.setWornList(worn);
        userRepo.save(me);
        return Map.of("ok", true,
                "wornBadges", me.getWornList(),
                "displayBadge", me.getDisplayBadge() == null ? "" : me.getDisplayBadge());
    }
}
