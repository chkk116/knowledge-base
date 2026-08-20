package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.FileService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/files")
public class AdminFileController {

    private final FileService fileSvc;
    private final DocumentRepository docRepo;
    private final UserRepository userRepo;

    public AdminFileController(FileService fileSvc, DocumentRepository docRepo, UserRepository userRepo) {
        this.fileSvc = fileSvc;
        this.docRepo = docRepo;
        this.userRepo = userRepo;
    }

    /** 孤儿文件列表（仅管理员）：未被任何文档/用户头像引用的上传图片 */
    @GetMapping("/orphans")
    public List<Map<String, Object>> orphans(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        Set<String> avatars = userRepo.findAll().stream()
                .map(User::getAvatar)
                .filter(Objects::nonNull)
                .filter(s -> s.startsWith("/uploads/"))
                .collect(Collectors.toSet());
        return fileSvc.findOrphans(docRepo.findAll(), avatars);
    }

    /** 删除单个孤儿文件（仅管理员） */
    @DeleteMapping("/orphan")
    public Map<String, Object> deleteOrphan(@RequestAttribute("kbUser") TokenClaims c,
                                            @RequestParam String url) {
        requireAdmin(c);
        fileSvc.deleteByUrl(url);
        return Map.of("ok", true);
    }

    private void requireAdmin(TokenClaims c) {
        if (c == null || !"ADMIN".equals(c.role())) {
            throw new ResponseStatusException(FORBIDDEN, "需要管理员权限");
        }
    }
}
