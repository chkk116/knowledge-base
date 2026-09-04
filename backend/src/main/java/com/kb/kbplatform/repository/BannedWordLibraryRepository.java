package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.BannedWordLibrary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BannedWordLibraryRepository extends JpaRepository<BannedWordLibrary, Long> {
    List<BannedWordLibrary> findByEnabledTrue();
    Optional<BannedWordLibrary> findByName(String name);
    Optional<BannedWordLibrary> findByBuiltinTrueAndName(String name);
    List<BannedWordLibrary> findByBuiltinTrue();
    List<BannedWordLibrary> findByEnabledTrueOrderByNameAsc();
}
