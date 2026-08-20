package com.kb.kbplatform.web;

import com.kb.kbplatform.service.AnnouncementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 公开接口：所有人（含未登录）可读取已启用公告。
 */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    private final AnnouncementService svc;

    public AnnouncementController(AnnouncementService svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return svc.listActive().stream().map(svc::toMap).collect(Collectors.toList());
    }
}
