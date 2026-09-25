package com.smartstaff.controller;

import com.smartstaff.dto.response.QuestionBankResponse;
import com.smartstaff.dto.response.QuestionUploadResultResponse;
import com.smartstaff.dto.response.SimpleResponse;
import com.smartstaff.entity.User;
import com.smartstaff.service.QuestionBankService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/questions")
public class QuestionBankController {

    private final QuestionBankService questionBankService;

    public QuestionBankController(QuestionBankService questionBankService) {
        this.questionBankService = questionBankService;
    }

    @GetMapping("/bank")
    public ResponseEntity<QuestionBankResponse> getBank(@RequestParam(defaultValue = "1") int limit) {
        return ResponseEntity.ok(questionBankService.getBank());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuestionUploadResultResponse> upload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User admin
    ) {
        return ResponseEntity.ok(questionBankService.upload(file, admin));
    }

    @DeleteMapping("/upload/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimpleResponse> deleteUpload(@PathVariable UUID id) {
        questionBankService.deleteUpload(id);
        return ResponseEntity.ok(SimpleResponse.OK);
    }

    @PostMapping("/clear")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimpleResponse> clear() {
        questionBankService.clear();
        return ResponseEntity.ok(SimpleResponse.OK);
    }
}
