package com.smartstaff.controller;

import com.smartstaff.dto.response.QuestionBookResponse;
import com.smartstaff.entity.QuestionType;
import com.smartstaff.exception.ApiException;
import com.smartstaff.service.impl.QuestionBookService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The company question book: search it by type, skill and difficulty. Any signed-in employee
 *  can search it and add to a paper; only an admin can remove a question from it. */
@RestController
@RequestMapping("/api/question-book")
public class QuestionBookController {

    private final QuestionBookService service;

    public QuestionBookController(QuestionBookService service) {
        this.service = service;
    }

    @GetMapping
    public List<QuestionBookResponse> search(@RequestParam(required = false) String type,
                                             @RequestParam(required = false) String difficulty,
                                             @RequestParam(required = false) String skill,
                                             @RequestParam(required = false) String text) {
        return service.search(parseType(type), difficulty, skill, text);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    private static QuestionType parseType(String type) {
        if (type == null || type.isBlank()) return null;
        try {
            return QuestionType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown question type.", "unknown_type");
        }
    }
}
