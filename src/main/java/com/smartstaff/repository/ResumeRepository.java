package com.smartstaff.repository;

import com.smartstaff.entity.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {

    List<Resume> findByJobIdOrderByUploadedAtDesc(UUID jobId);

    Optional<Resume> findByJobIdAndFilename(UUID jobId, String filename);
}
