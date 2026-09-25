package com.smartstaff.repository;

import com.smartstaff.entity.QuestionBankUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuestionBankUploadRepository extends JpaRepository<QuestionBankUpload, UUID> {

    List<QuestionBankUpload> findAllByOrderByCreatedAtAsc();
}
