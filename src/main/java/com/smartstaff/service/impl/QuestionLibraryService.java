package com.smartstaff.service.impl;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One searchable list of questions from the three places they live: the company question book,
 *  questions already in papers, and the older HR question bank. Read-only; the book's own
 *  delete is still on QuestionBookController. */
@Service
public class QuestionLibraryService {

    static final int PER_SOURCE_LIMIT = 200;

    private final NamedParameterJdbcTemplate jdbc;

    public QuestionLibraryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String source, String type, String difficulty, String skill, String text) {
        String src = source == null ? "ALL" : source.trim().toUpperCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        if (src.equals("ALL") || src.equals("BOOK")) parts.add(BOOK_SQL);
        if (src.equals("ALL") || src.equals("PAPER")) parts.add(PAPER_SQL);
        if (src.equals("ALL") || src.equals("HR")) parts.add(HR_SQL);
        if (parts.isEmpty()) return List.of();

        var params = new MapSqlParameterSource()
                .addValue("type", blank(type) ? "" : type.trim().toUpperCase(Locale.ROOT))
                .addValue("difficulty", blank(difficulty) ? "" : difficulty.trim().toUpperCase(Locale.ROOT))
                .addValue("skill", blank(skill) ? "%" : "%" + skill.trim().toLowerCase(Locale.ROOT) + "%")
                .addValue("text", blank(text) ? "%" : "%" + text.trim().toLowerCase(Locale.ROOT) + "%")
                .addValue("limit", PER_SOURCE_LIMIT);

        // Each source gets its own limit, so a large paper bank cannot push the book's rows out of the list.
        String union = String.join(" union all ", parts.stream()
                .map(p -> "(" + p + " order by prompt limit :limit)").toList());
        String sql = "select * from (" + union + ") q order by source, prompt";
        return jdbc.queryForList(sql, params);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // Each branch returns the same columns: source, id, type, skill, difficulty, marks, prompt, origin, usage.
    // Filters compare in upper case because difficulty is stored in mixed case in the older tables.
    private static final String FILTERS = """
            and (:type = '' or upper(type) = :type)
            and (:difficulty = '' or upper(difficulty) = :difficulty)
            and lower(coalesce(skill, '')) like :skill
            and lower(prompt) like :text
            """;

    private static final String BOOK_SQL = """
            select 'BOOK' as source, id::text as id, type, skill, upper(difficulty) as difficulty, marks,
                   prompt, origin, usage_count as usage, content::text as answer
            from question_book_items
            where 1 = 1
            """ + FILTERS;

    private static final String PAPER_SQL = """
            select 'PAPER' as source, id::text as id, type, skill, upper(difficulty) as difficulty,
                   points as marks, prompt, origin, 0 as usage,
                   json_build_object('options', options, 'correct_indices', correct_indices,
                       'model_answer', model_answer, 'key_points', key_points,
                       'accepted_answers', accepted_answers, 'buggy_code', buggy_code,
                       'reference_solution', reference_solution, 'explanation', explanation)::text as answer
            from assessment_questions
            where 1 = 1
            """ + FILTERS;

    private static final String HR_SQL = """
            select 'HR' as source, id::text as id, type, skill, upper(difficulty) as difficulty,
                   points as marks, prompt, coalesce(source, 'HR') as origin, 0 as usage,
                   json_build_object('options', options, 'correct_indices', correct_indices,
                       'model_answer', model_answer, 'key_points', key_points,
                       'buggy_code', buggy_code, 'reference_solution', reference_solution,
                       'explanation', explanation)::text as answer
            from question_bank_items
            where 1 = 1
            """ + FILTERS;
}
