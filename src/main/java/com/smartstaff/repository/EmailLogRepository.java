package com.smartstaff.repository;

import com.smartstaff.entity.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EmailLogRepository extends JpaRepository<EmailLog, UUID> {

    List<EmailLog> findTop200ByJobIdOrderByCreatedAtDesc(UUID jobId);
}
