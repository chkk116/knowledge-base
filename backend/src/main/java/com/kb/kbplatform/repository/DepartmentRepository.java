package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, Long> {

    /** 取某父部门下的直属子部门（按排序号升序） */
    List<Department> findByParentIdOrderBySortOrderAsc(Long parentId);

    /** 取所有顶级部门（parentId 为 null） */
    List<Department> findByParentIdIsNullOrderBySortOrderAsc();

    /** 全部部门（按排序号升序），用于前端组装完整树 */
    List<Department> findAllByOrderBySortOrderAsc();
}
