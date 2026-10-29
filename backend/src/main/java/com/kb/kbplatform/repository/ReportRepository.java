package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Report;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {
    List<Report> findByStatusOrderByIdDesc(String status, Pageable page);
    List<Report> findAllByOrderByIdDesc(Pageable page);
    List<Report> findByReporterIdOrderByIdDesc(Long reporterId, Pageable page);
    long countByStatus(String status);
}
