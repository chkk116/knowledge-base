package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.DocShare;
import com.kb.kbplatform.domain.DocShareCapacityRequest;
import com.kb.kbplatform.domain.DocShareCreateRequest;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuditService;
import com.kb.kbplatform.service.DocShareCapacityRequestService;
import com.kb.kbplatform.service.DocShareCreateRequestService;
import com.kb.kbplatform.service.DocShareService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/admin/doc-shares")
public class AdminDocShareController {

    private final DocShareService svc;
    private final UserRepository userRepo;
    private final AuditService audit;
    private final DocShareCapacityRequestService capacityReqSvc;
    private final DocShareCreateRequestService createReqSvc;

    public AdminDocShareController(DocShareService svc, UserRepository userRepo, AuditService audit,
                                   DocShareCapacityRequestService capacityReqSvc,
                                   DocShareCreateRequestService createReqSvc) {
        this.svc = svc;
        this.userRepo = userRepo;
        this.audit = audit;
        this.capacityReqSvc = capacityReqSvc;
        this.createReqSvc = createReqSvc;
    }

    @GetMapping
    public List<DocShare> list(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "SHARE_VIEW");
        return svc.listAll(resolveUserId(c), true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "SHARE_DELETE");
        DocShare s = svc.get(id, resolveUserId(c), true);
        String name = s.getName();
        svc.delete(id, resolveUserId(c), true);
        audit.log(c.userId(), c.username(), "DELETE_SHARE_SPACE", "SHARE_SPACE", id, name, "管理员删除团队空间「" + name + "」");
        return Map.of("ok", true);
    }

    /** 完全管理员设置某空间的容量上限（字节），capacity=0 表示取消上限 */
    @PutMapping("/{id}/capacity")
    public DocShare setCapacity(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                @RequestBody Map<String, Object> body) {
        requireAdmin(c);
        Long cap = lng(body.get("capacity"));
        DocShare s = svc.get(id, resolveUserId(c), true);
        DocShare updated = svc.update(id, null, null, null, cap, resolveUserId(c), true);
        String capText = cap == null || cap == 0L ? "不限" : DocShareService.formatSize(cap);
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_CAPACITY", "SHARE_SPACE", id, s.getName(),
                "设置空间「" + s.getName() + "」容量上限为 " + capText);
        return updated;
    }

    // ---- 空间扩容申请审批（仅完全管理员） ----
    /** 待审批列表 */
    @GetMapping("/capacity-requests")
    public List<DocShareCapacityRequest> listCapacityRequests(@RequestAttribute("kbUser") TokenClaims c) {
        return capacityReqSvc.listPending(c);
    }

    /** 审批通过：自动调整空间容量 */
    @PostMapping("/capacity-requests/{rid}/approve")
    public DocShareCapacityRequest approve(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long rid) {
        return capacityReqSvc.approve(rid, c);
    }

    /** 驳回 */
    @PostMapping("/capacity-requests/{rid}/reject")
    public DocShareCapacityRequest reject(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long rid,
                                          @RequestBody Map<String, Object> body) {
        return capacityReqSvc.reject(rid, c, str(body.get("comment")));
    }

    // ---- 团队空间创建申请审批（仅完全管理员） ----
    /** 待审批列表 */
    @GetMapping("/create-requests")
    public List<DocShareCreateRequest> listCreateRequests(@RequestAttribute("kbUser") TokenClaims c) {
        return createReqSvc.listPending(c);
    }

    /** 审批通过：自动创建空间（申请人成为所有者） */
    @PostMapping("/create-requests/{rid}/approve")
    public DocShareCreateRequest approveCreate(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long rid) {
        return createReqSvc.approve(rid, c);
    }

    /** 驳回 */
    @PostMapping("/create-requests/{rid}/reject")
    public DocShareCreateRequest rejectCreate(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long rid,
                                              @RequestBody Map<String, Object> body) {
        return createReqSvc.reject(rid, c, str(body.get("comment")));
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要「" + code + "」权限，请联系系统管理员开通");
        }
    }

    private void requireAdmin(TokenClaims c) {
        if (c == null || !"ADMIN".equals(c.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要系统管理员（完全管理员）权限");
        }
    }

    private Long resolveUserId(TokenClaims c) {
        if (c == null || c.username() == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        return userRepo.findByUsername(c.username()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "请先登录"));
    }

    private Long lng(Object o) { return o == null ? null : Long.valueOf(String.valueOf(o)); }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
