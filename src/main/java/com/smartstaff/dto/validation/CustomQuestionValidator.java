package com.smartstaff.dto.validation;

import com.smartstaff.dto.request.CustomQuestionRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Checks the fields each question type needs, reporting each problem on its field. */
public class CustomQuestionValidator implements ConstraintValidator<ValidCustomQuestion, CustomQuestionRequest> {

    @Override
    public boolean isValid(CustomQuestionRequest q, ConstraintValidatorContext ctx) {
        if (q == null || q.type() == null) return true;   // @NotNull reports a missing type
        ctx.disableDefaultConstraintViolation();
        return switch (q.type()) {
            case MCQ -> mcq(q, ctx);
            case ONE_WORD -> oneWord(q, ctx);
            case SCENARIO -> scenario(q, ctx);
            case PROJECT -> project(q, ctx);
            default -> fail(ctx, "type", "Only MCQ, ONE_WORD, SCENARIO and PROJECT questions can be authored here.");
        };
    }

    private static boolean mcq(CustomQuestionRequest q, ConstraintValidatorContext ctx) {
        List<String> options = q.options();
        if (options == null || options.size() < 2) return fail(ctx, "options", "An MCQ needs 2 to 6 options.");
        if (new HashSet<>(options.stream().map(o -> o.trim().toLowerCase(Locale.ROOT)).toList()).size() != options.size()) {
            return fail(ctx, "options", "Options must be distinct.");
        }
        if (q.correct_index() == null || q.correct_index() >= options.size()) {
            return fail(ctx, "correct_index", "correct_index must point at one of the options (0-based).");
        }
        return true;
    }

    private static boolean oneWord(CustomQuestionRequest q, ConstraintValidatorContext ctx) {
        if (blank(q.answer())) return fail(ctx, "answer", "A one-word question needs its answer.");
        if (q.answer().trim().split("\\s+").length > 3) return fail(ctx, "answer", "The answer can be at most 3 words.");
        return true;
    }

    private static boolean scenario(CustomQuestionRequest q, ConstraintValidatorContext ctx) {
        if (blank(q.model_answer())) return fail(ctx, "model_answer", "A scenario question needs a model answer.");
        if (q.key_points() == null || q.key_points().size() < 4) {
            return fail(ctx, "key_points", "A scenario question needs 4 to 6 key points to grade against.");
        }
        return true;
    }

    private static boolean project(CustomQuestionRequest q, ConstraintValidatorContext ctx) {
        if (q.rubric() == null || q.rubric().isEmpty()) {
            return fail(ctx, "rubric", "A project question needs at least one rubric criterion.");
        }
        return true;
    }

    private static boolean fail(ConstraintValidatorContext ctx, String field, String message) {
        ctx.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
        return false;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
