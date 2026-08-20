package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByParentIdOrderBySortOrderAscIdAsc(Long parentId);
    List<Category> findAllByOrderBySortOrderAscIdAsc();
}
