package com.smartstaff.service;

import com.smartstaff.dto.response.QuestionBankResponse;
import com.smartstaff.dto.response.QuestionUploadResultResponse;
import com.smartstaff.entity.User;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface QuestionBankService {

    QuestionBankResponse getBank();

    QuestionUploadResultResponse upload(MultipartFile file, User admin);

    void deleteUpload(UUID uploadId);

    void clear();
}
