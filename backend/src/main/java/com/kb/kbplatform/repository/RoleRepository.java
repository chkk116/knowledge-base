package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(String name);

    Optional<Role> findByCode(String code);

    /** 全部角色（按排序号升序） */
    List<Role> findAllByOrderBySortOrderAsc();
}
