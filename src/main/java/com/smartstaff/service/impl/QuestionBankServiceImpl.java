package com.smartstaff.service.impl;

import com.smartstaff.dto.response.*;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.QuestionBankItemRepository;
import com.smartstaff.repository.QuestionBankUploadRepository;
import com.smartstaff.service.QuestionBankService;
import com.smartstaff.util.QuestionBankFileParser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
public class QuestionBankServiceImpl implements QuestionBankService {

    private final QuestionBankUploadRepository uploadRepository;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankFileParser parser;
    private final QuestionValidator validator;
    private final TransactionTemplate transaction;

    public QuestionBankServiceImpl(QuestionBankUploadRepository uploadRepository,
                                    QuestionBankItemRepository itemRepository,
                                    QuestionBankFileParser parser,
                                    QuestionValidator validator,
                                    PlatformTransactionManager transactionManager) {
        this.uploadRepository = uploadRepository;
        this.itemRepository = itemRepository;
        this.parser = parser;
        this.validator = validator;
        this.transaction = new TransactionTemplate(transactionManager);
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

    /** Parses, then proves every question with the same validator generation
     *  uses (coding ones run in the sandbox) before anything is saved; rows
     *  that fail are skipped with the reason. The sandbox runs happen outside
     *  the database transaction. */
    @Override
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

        List<String> warnings = new ArrayList<>(result.warnings());
        List<QuestionBankFileParser.ParsedQuestion> accepted = new ArrayList<>();
        for (var parsed : result.questions()) {
            QuestionValidator.Result check = validator.validate(
                    new QuestionDraft(parsed.question(), parsed.naiveSolution()), Set.of(), null);
            if (check.ok()) {
                parsed.question().setValidated(true);
                parsed.question().setValidationLog(check.log());
                accepted.add(parsed);
            } else {
                warnings.add("Row " + parsed.row() + " (" + parsed.type() + "): " + check.reason() + " — skipped.");
            }
        }

        if (accepted.isEmpty()) {
            return QuestionUploadResultResponse.failure(
                    warnings.isEmpty() ? "No valid questions found in that file."
                            : "No valid questions found — " + String.join(" ", warnings));
        }

        transaction.executeWithoutResult(status -> {
            QuestionBankUpload upload = new QuestionBankUpload(
                    file.getOriginalFilename(), admin == null ? null : admin.publicId(), accepted.size());
            uploadRepository.save(upload);
            for (var parsed : accepted) itemRepository.save(toItem(parsed, upload));
        });

        int total = (int) itemRepository.count();
        return QuestionUploadResultResponse.success(accepted.size(), total, warnings);
    }

    private static QuestionBankItem toItem(QuestionBankFileParser.ParsedQuestion parsed, QuestionBankUpload upload) {
        AssessmentQuestion q = parsed.question();
        QuestionBankItem item = new QuestionBankItem();
        item.setUpload(upload);
        item.setType(q.getType().name());
        item.setDimension(q.getDimension());
        item.setCompetency(q.getCompetency());
        item.setLevel(parsed.level());
        item.setSkill(q.getSkill());
        item.setDifficulty(q.getDifficulty());
        item.setPoints(q.getPoints() > 0 ? q.getPoints() : null);
        item.setTimeEstimateSec(q.getTimeEstimateSec());
        item.setTitle(q.getTitle());
        item.setPrompt(q.getPrompt());
        item.setConstraints(q.getConstraints());
        item.setInputFormat(q.getInputFormat());
        item.setOutputFormat(q.getOutputFormat());
        item.setOptions(q.getOptions());
        item.setCorrectIndices(q.getCorrectIndices());
        item.setLanguages(q.getLanguages());
        item.setStarterCode(q.getStarterCode());
        item.setReferenceSolution(q.getReferenceSolution());
        item.setNaiveSolution(new LinkedHashMap<>(parsed.naiveSolution()));
        item.setBuggyCode(q.getBuggyCode());
        item.setModelAnswer(q.getModelAnswer());
        item.setKeyPoints(q.getKeyPoints());
        item.setRubric(q.getRubric());
        item.setExplanation(q.getExplanation());
        item.setExpectedComplexity(q.getExpectedComplexity());
        item.setBugDescriptions(q.getBugDescriptions());
        item.setRoleFamilies(parsed.roleFamilies());
        item.setValidated(q.isValidated());
        item.setValidationLog(q.getValidationLog());
        for (QuestionTestCase t : q.getTestCases()) {
            QuestionBankTestCase tc = new QuestionBankTestCase();
            tc.setItem(item);
            tc.setSeq(t.getSeq());
            tc.setInput(t.getInput());
            tc.setExpectedOutput(t.getExpectedOutput());
            tc.setVisible(t.isVisible());
            tc.setCategory(t.getCategory());
            tc.setWeight(t.getWeight());
            tc.setFloatTolerance(t.getFloatTolerance());
            item.getTestCases().add(tc);
        }
        return item;
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
