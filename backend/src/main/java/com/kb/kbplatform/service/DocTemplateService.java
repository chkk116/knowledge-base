/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DocTemplate;
import com.kb.kbplatform.repository.DocTemplateRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 文档模板：列表对所有登录用户开放（新建文档时选用），维护仅管理员。 */
@Service
public class DocTemplateService {

    private final DocTemplateRepository repo;

    public DocTemplateService(DocTemplateRepository repo) {
        this.repo = repo;
    }

    public List<DocTemplate> list() {
        return repo.findAllByOrderBySortOrderAscIdAsc();
    }

    public DocTemplate create(DocTemplate t, Long userId) {
        if (t.getName() == null || t.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板名称不能为空");
        t.setId(null);
        if (t.getName().length() > 100) t.setName(t.getName().substring(0, 100));
        t.setCreatedBy(userId);
        return repo.save(t);
    }

    public DocTemplate update(Long id, DocTemplate patch) {
        DocTemplate t = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));
        if (patch.getName() != null && !patch.getName().isBlank()) t.setName(patch.getName().trim());
        if (patch.getCategory() != null) t.setCategory(patch.getCategory());
        if (patch.getDescription() != null) t.setDescription(patch.getDescription());
        if (patch.getContent() != null) t.setContent(patch.getContent());
        t.setSortOrder(patch.getSortOrder());
        return repo.save(t);
    }

    public void delete(Long id) {
        if (!repo.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在");
        repo.deleteById(id);
    }
}
