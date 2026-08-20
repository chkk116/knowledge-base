package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Tag;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.TagRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TagService {

    private final TagRepository repo;
    private final DocumentRepository docRepo;

    public TagService(TagRepository repo, DocumentRepository docRepo) {
        this.repo = repo;
        this.docRepo = docRepo;
    }

    /** 标签列表（带已发布文档计数，按计数倒序），用于标签体系管理与标签云 */
    public List<Map<String, Object>> listWithCount() {
        List<Tag> tags = repo.findAll();
        List<Map<String, Object>> res = new ArrayList<>();
        for (Tag t : tags) {
            long cnt = docRepo.findByTag(t.getName(), PageRequest.of(0, 1)).getTotalElements();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("count", cnt);
            res.add(m);
        }
        res.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
        return res;
    }

    public Tag create(String name) {
        name = (name == null ? "" : name.trim());
        if (name.isEmpty()) throw new IllegalArgumentException("标签名不能为空");
        if (name.length() > 64) throw new IllegalArgumentException("标签名过长（上限 64 字符）");
        if (repo.findByName(name).isPresent()) throw new IllegalStateException("标签已存在");
        Tag t = new Tag();
        t.setName(name);
        return repo.save(t);
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }
}
