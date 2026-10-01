package com.smartstaff.repository;

import com.smartstaff.entity.ActivityEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ActivityEventRepository extends JpaRepository<ActivityEvent, Long> {

    List<ActivityEvent> findByJobIdOrderByAtDescIdDesc(UUID jobId, Pageable page);
}
