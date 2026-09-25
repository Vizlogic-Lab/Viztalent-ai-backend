package com.smartstaff.service.impl;

import com.smartstaff.dto.response.*;
import com.smartstaff.entity.QuestionBankItem;
import com.smartstaff.entity.QuestionBankUpload;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.QuestionBankItemRepository;
import com.smartstaff.repository.QuestionBankUploadRepository;
import com.smartstaff.service.QuestionBankService;
import com.smartstaff.util.QuestionBankFileParser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QuestionBankServiceImpl implements QuestionBankService {

    private final QuestionBankUploadRepository uploadRepository;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankFileParser parser;

    public QuestionBankServiceImpl(QuestionBankUploadRepository uploadRepository,
                                    QuestionBankItemRepository itemRepository,
                                    QuestionBankFileParser parser) {
        this.uploadRepository = uploadRepository;
        this.itemRepository = itemRepository;
        this.parser = parser;
    }

    @Override
    public QuestionBankResponse getBank() {
        List<QuestionBankUpload> uploads = uploadRepository.findAllByOrderByCreatedAtAsc();

        Map<String, Long> byType = new LinkedHashMap<>();
        for (var row : itemRepository.countGroupedByType()) byType.put(row.getType(), row.getCnt());
        Map<String, Long> byLevel = new LinkedHashMap<>();
        for (var row : itemRepository.countGroupedByLevel()) byLevel.put(row.getLevel(), row.getCnt());

        int total = (int) byType.values().stream().mapToLong(Long::longValue).sum();

        var stats = new QuestionBankStatsResponse(total, byType, byLevel, uploads.size());
        var uploadRows = uploads.stream()
                .map(u -> new QuestionUploadSummaryResponse(u.getId().toString(), u.getFilename(), u.getItemCount(), u.getCreatedAt()))
                .toList();

        return new QuestionBankResponse(true, stats, uploadRows);
    }

    @Override
    @Transactional
    public QuestionUploadResultResponse upload(MultipartFile file, User admin) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return QuestionUploadResultResponse.failure("Could not read the uploaded file.");
        }

        QuestionBankFileParser.ParseResult result;
        try {
            result = parser.parse(bytes, file.getOriginalFilename());
        } catch (IOException e) {
            return QuestionUploadResultResponse.failure(e.getMessage());
        }

        if (result.questions().isEmpty()) {
            return QuestionUploadResultResponse.failure(
                    result.warnings().isEmpty() ? "No valid questions found in that file."
                            : "No valid questions found — " + String.join(" ", result.warnings()));
        }

        QuestionBankUpload upload = new QuestionBankUpload(
                file.getOriginalFilename(), admin == null ? null : admin.publicId(), result.questions().size());
        uploadRepository.save(upload);

        for (var q : result.questions()) {
            QuestionBankItem item = new QuestionBankItem();
            item.setUpload(upload);
            item.setType(q.type());
            item.setLevel(q.level());
            item.setSkill(q.skill());
            item.setDifficulty(q.difficulty());
            item.setPrompt(q.prompt());
            item.setOptions(q.options());
            item.setCorrectIndices(q.correctIndices());
            itemRepository.save(item);
        }

        int total = (int) itemRepository.count();
        return QuestionUploadResultResponse.success(result.questions().size(), total, result.warnings());
    }

    @Override
    @Transactional
    public void deleteUpload(UUID uploadId) {
        if (!uploadRepository.existsById(uploadId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Upload not found.");
        }
        uploadRepository.deleteById(uploadId); // cascades to question_bank_items
    }

    @Override
    @Transactional
    public void clear() {
        uploadRepository.deleteAll(); // cascades to question_bank_items
    }
}
