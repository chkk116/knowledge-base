package com.kb.kbplatform.web;

import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.FileService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/files")
public class AdminFileController {

    private static final Pattern AUDIO_EXT = Pattern.compile("\\.(mp3|wav|ogg|webm|m4a|flac|aac)$", Pattern.CASE_INSENSITIVE);

    private final FileService fileSvc;
    private final DocumentRepository docRepo;
    private final UserRepository userRepo;

    public AdminFileController(FileService fileSvc, DocumentRepository docRepo, UserRepository userRepo) {
        this.fileSvc = fileSvc;
        this.docRepo = docRepo;
        this.userRepo = userRepo;
    }

    /** 孤儿文件列表（仅管理员）：未被任何文档/用户头像引用的上传文件。
     *  服务端分页 + 类型/关键字过滤：孤儿集需要全量扫描（目录遍历 + 正文正则），
     *  但响应体按页截断，前端不再一次性接收整个列表。 */
    @GetMapping("/orphans")
    public Map<String, Object> orphans(@RequestAttribute("kbUser") TokenClaims c,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(defaultValue = "all") String kind,
                                       @RequestParam(required = false) String q) {
        requireAdmin(c);
        Set<String> avatars = new HashSet<>(userRepo.findAllUploadAvatars());
        avatars.addAll(userRepo.findAllPendingUploadAvatars());   // 待审头像同样占用上传目录，避免被当孤儿清掉
        List<Map<String, Object>> all = fileSvc.findOrphans(docRepo.findAllContents(), avatars);

        // 类型统计（过滤前）
        long imageCount = all.stream().filter(f -> !isAudio(str(f.get("url")))).count();
        long audioCount = all.size() - imageCount;

        // 过滤：类型（all/image/audio）+ 路径关键字
        String kw = (q == null) ? "" : q.trim().toLowerCase();
        List<Map<String, Object>> filtered = all.stream().filter(f -> {
            String url = str(f.get("url"));
            if ("image".equals(kind) && isAudio(url)) return false;
            if ("audio".equals(kind) && !isAudio(url)) return false;
            return kw.isEmpty() || url.toLowerCase().contains(kw);
        }).toList();

        // 分页切片（参数钳制，防越界/超大页）
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 200);
        int total = filtered.size();
        int from = Math.min((p - 1) * s, total);
        int to = Math.min(from + s, total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", filtered.subList(from, to));
        result.put("total", total);
        result.put("page", p);
        result.put("size", s);
        result.put("imageCount", imageCount);
        result.put("audioCount", audioCount);
        return result;
    }

    private static boolean isAudio(String url) {
        return url != null && AUDIO_EXT.matcher(url).find();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
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
        if (c == null || !c.isAdmin()) {
            throw new ResponseStatusException(FORBIDDEN, "需要管理员权限");
        }
    }
}
