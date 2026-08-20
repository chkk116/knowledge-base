package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.PointsLog;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.PointsLogRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/points")
public class PointsController {

    private final UserRepository userRepo;
    private final PointsLogRepository pointsLogRepo;

    public PointsController(UserRepository userRepo, PointsLogRepository pointsLogRepo) {
        this.userRepo = userRepo;
        this.pointsLogRepo = pointsLogRepo;
    }

    /** 积分排行榜：返回积分最高的前 20 名，并标注当前登录用户的排名。
     *  排行榜与名次均走数据库侧（ORDER BY + LIMIT / COUNT），不再全表加载到内存排序。 */
    @GetMapping("/ranking")
    public Map<String, Object> ranking(@RequestAttribute("kbUser") TokenClaims c) {
        // 数据库侧排序 + 取前 20（points 降序、同名按 username 升序）
        List<User> top = userRepo.findAll(
                PageRequest.of(0, 20,
                        Sort.by(Sort.Direction.DESC, "points").and(Sort.by(Sort.Direction.ASC, "username")))
        ).getContent();

        List<Map<String, Object>> topList = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            User u = top.get(i);
            String displayName = u.getDisplayName() == null || u.getDisplayName().isBlank()
                    ? u.getUsername() : u.getDisplayName();
            topList.add(Map.of(
                    "rank", i + 1,
                    "username", u.getUsername(),
                    "displayName", displayName,
                    "avatar", u.getAvatar() == null ? "" : u.getAvatar(),
                    "points", u.getPoints()
            ));
        }

        // 当前用户名次：用 COUNT 算出「排在我前面的人数」，避免全表扫描
        User me = userRepo.findByUsername(c.username()).orElse(null);
        int myPoints = me == null ? 0 : me.getPoints();
        long above = userRepo.countRankedAbove(myPoints, c.username());
        int meRank = (int) above + 1;

        return Map.of(
                "top", topList,
                "me", Map.of(
                        "rank", meRank,
                        "points", myPoints,
                        "username", c.username()
                ),
                "total", userRepo.count()
        );
    }

    /** 当前登录用户的积分明细（最近 50 条，按时间倒序） */
    @GetMapping("/log")
    public List<Map<String, Object>> log(@RequestAttribute("kbUser") TokenClaims c) {
        List<PointsLog> logs = pointsLogRepo.findByUsernameOrderByCreatedAtDesc(c.username());
        int limit = Math.min(logs.size(), 50);
        List<Map<String, Object>> res = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            PointsLog l = logs.get(i);
            res.add(Map.of(
                    "id", l.getId(),
                    "delta", l.getDelta(),
                    "type", l.getType(),
                    "reason", l.getReason() == null ? "" : l.getReason(),
                    "balance", l.getBalance(),
                    "createdAt", l.getCreatedAt() == null ? "" : l.getCreatedAt().toString()
            ));
        }
        return res;
    }

    /** 积分明细（分页 + 类型筛选）：明细页「查看完整明细」使用。
     *  返回当前页内容、总条数、累计获得 / 支出，便于前端做汇总卡片与分页。 */
    @GetMapping("/log/paged")
    public Map<String, Object> logPaged(@RequestAttribute("kbUser") TokenClaims c,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String type) {
        int pg = Math.max(page, 0);
        int sz = Math.max(size, 1);
        Page<PointsLog> p = (type == null || type.isBlank())
                ? pointsLogRepo.findByUsernameOrderByCreatedAtDesc(c.username(),
                        PageRequest.of(pg, sz, Sort.by(Sort.Direction.DESC, "createdAt")))
                : pointsLogRepo.findByUsernameAndTypeOrderByCreatedAtDesc(c.username(), type,
                        PageRequest.of(pg, sz, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Map<String, Object>> content = new ArrayList<>();
        for (PointsLog l : p.getContent()) {
            content.add(Map.of(
                    "id", l.getId(),
                    "delta", l.getDelta(),
                    "type", l.getType(),
                    "reason", l.getReason() == null ? "" : l.getReason(),
                    "balance", l.getBalance(),
                    "createdAt", l.getCreatedAt() == null ? "" : l.getCreatedAt().toString()
            ));
        }
        int earned = pointsLogRepo.sumEarned(c.username());   // 正数：累计获得
        int spent = pointsLogRepo.sumSpent(c.username());      // 负数：累计支出
        return Map.of(
                "content", content,
                "total", p.getTotalElements(),
                "page", p.getNumber(),
                "size", p.getSize(),
                "pages", p.getTotalPages(),
                "earned", earned,
                "spent", spent < 0 ? -spent : spent
        );
    }
}
