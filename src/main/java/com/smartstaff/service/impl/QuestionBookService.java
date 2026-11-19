package com.smartstaff.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.dto.request.CustomQuestionRequest;
import com.smartstaff.dto.response.QuestionBookResponse;
import com.smartstaff.entity.QuestionBookItem;
import com.smartstaff.entity.QuestionType;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.QuestionBookItemRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** The company-wide question book. Questions are saved here when an employer keeps them
 *  (or when the AI writes them), and copied into papers when picked. A question with the
 *  same type and wording is stored once. */
@Service
public class QuestionBookService {

    static final int SEARCH_LIMIT = 50;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final QuestionBookItemRepository items;
    private final ObjectMapper objectMapper;

    public QuestionBookService(QuestionBookItemRepository items, ObjectMapper objectMapper) {
        this.items = items;
        this.objectMapper = objectMapper;
    }

    /** Saves a question to the book unless the same one is already there. origin is AI or MANUAL. */
    @Transactional
    public void save(CustomQuestionRequest req, String origin, String createdBy) {
        String hash = hash(req.type(), req.prompt());
        if (items.existsByPromptHash(hash)) return;
        QuestionBookItem b = new QuestionBookItem();
        b.setType(req.type());
        b.setSkill(blankToNull(req.skill()));
        b.setDifficulty(req.difficulty() == null ? null : req.difficulty().trim().toUpperCase(Locale.ROOT));
        b.setMarks(req.marks() == null ? 1 : req.marks());
        b.setPrompt(req.prompt().strip());
        b.setPromptHash(hash);
        b.setContent(objectMapper.convertValue(withoutFlags(req), MAP));
        b.setOrigin(origin);
        b.setCreatedBy(createdBy);
        items.save(b);
    }

    @Transactional(readOnly = true)
    public List<QuestionBookResponse> search(QuestionType type, String difficulty, String skill, String text) {
        return items.search(type == null ? "" : type.name(), normal(difficulty), orEmpty(skill), orEmpty(text),
                        PageRequest.of(0, SEARCH_LIMIT))
                .stream().map(QuestionBookService::view).toList();
    }

    /** The question as a paper-editor request, ready to copy into a paper. */
    @Transactional
    public CustomQuestionRequest copy(UUID id) {
        QuestionBookItem b = find(id);
        b.setUsageCount(b.getUsageCount() + 1);
        CustomQuestionRequest req = objectMapper.convertValue(b.getContent(), CustomQuestionRequest.class);
        return new CustomQuestionRequest(req.type(), req.prompt(), req.options(), req.correct_index(), req.answer(),
                req.accepted_answers(), req.model_answer(), req.key_points(), req.rubric(), b.getMarks(),
                b.getSkill(), b.getDifficulty(), null);
    }

    @Transactional
    public void delete(UUID id) {
        items.delete(find(id));
    }

    private QuestionBookItem find(UUID id) {
        return items.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "That question is not in the book.", "book_item_not_found"));
    }

    static QuestionBookResponse view(QuestionBookItem b) {
        return new QuestionBookResponse(b.getId(), b.getType().name(), b.getSkill(), b.getDifficulty(), b.getMarks(),
                b.getPrompt(), b.getOrigin(), b.getUsageCount(), b.getContent());
    }

    private CustomQuestionRequest withoutFlags(CustomQuestionRequest r) {
        return new CustomQuestionRequest(r.type(), r.prompt(), r.options(), r.correct_index(), r.answer(),
                r.accepted_answers(), r.model_answer(), r.key_points(), r.rubric(), r.marks(), r.skill(), r.difficulty(), null);
    }

    /** Lower-case wording with single spaces, so the same question typed twice matches. */
    static String hash(QuestionType type, String prompt) {
        String norm = type.name() + "|" + prompt.strip().toLowerCase(Locale.ROOT).replaceAll("\s+", " ");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(norm.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normal(String difficulty) {
        return difficulty == null || difficulty.isBlank() ? "" : difficulty.trim().toUpperCase(Locale.ROOT);
    }

    private static String orEmpty(String s) {
        return s == null || s.isBlank() ? "" : s.strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
