package com.smartstaff.repository;

import com.smartstaff.entity.AttemptEvent;
import com.smartstaff.entity.AttemptEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AttemptEventRepository extends JpaRepository<AttemptEvent, UUID> {

    long countByAttemptId(UUID attemptId);

    long countByAttemptIdAndType(UUID attemptId, AttemptEventType type);

    List<AttemptEvent> findByAttemptIdOrderByAtAsc(UUID attemptId);
}
