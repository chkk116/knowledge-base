package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AuditLog;
import com.kb.kbplatform.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作审计：记录管理员关键动作。best-effort——任何异常都吞掉，绝不影响主业务流程。
 */
@Service
public class AuditService {

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void log(Long actorId, String actorName, String action, String targetType, Long targetId, String targetName, String detail) {
        try {
            AuditLog a = new AuditLog();
            a.setActorId(actorId);
            a.setActorName(actorName);
            a.setAction(action);
            a.setTargetType(targetType);
            a.setTargetId(targetId);
            a.setTargetName(targetName);
            a.setDetail(detail);
            a.setIp(currentIp());
            repo.save(a);
        } catch (Exception ignored) {
            // 审计失败不影响主流程
        }
    }

    /** 尽量从请求上下文取客户端 IP（无请求上下文则返回 null） */
    private String currentIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String ip = req.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank()) return ip.split(",")[0].trim();
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
