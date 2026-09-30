package com.smartstaff.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.*;
import com.smartstaff.entity.JobRoleProfile.RoleFamily;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Parses question-bank uploads into unsaved questions. A bad row is skipped
 *  with a warning rather than failing the whole upload.
 *
 *  JSON carries every question type, including the practical fields (code
 *  per language, tests, rubric, model answers) — see
 *  resources/samples/question-bank-sample.json. CSV and XLSX are flat, so
 *  they carry theory and written types only (MCQ, MSQ, SCENARIO, LOGIC);
 *  coding rows there are skipped with a warning. The legacy type names
 *  DESCRIPTIVE and CODING still work and mean SCENARIO and CODE_WRITE.
 *
 *  Parsing checks shape only; QuestionValidator proves the questions. */
@Component
public class QuestionBankFileParser {

    private final ObjectMapper objectMapper;

    public QuestionBankFileParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** naiveSolution only proves the hidden tests catch flawed code. */
    public record ParsedQuestion(int row, String level, List<String> roleFamilies,
                                 AssessmentQuestion question, Map<String, String> naiveSolution) {
        public String type() { return question.getType().name(); }
        public String prompt() { return question.getPrompt(); }
        public String skill() { return question.getSkill(); }
        public String difficulty() { return question.getDifficulty(); }
        public List<String> options() { return question.getOptions(); }
        public List<Integer> correctIndices() { return question.getCorrectIndices(); }
    }

    public record ParseResult(List<ParsedQuestion> questions, List<String> warnings) {}

    public ParseResult parse(byte[] bytes, String filename) throws IOException {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) return parseJson(bytes);
        if (lower.endsWith(".xlsx")) return parseXlsx(bytes);
        if (lower.endsWith(".csv")) return parseCsv(bytes);
        throw new IOException("Unsupported file type — use .json, .csv, or .xlsx.");
    }

    // ── JSON (all types) ────────────────────────────────────────────────

    private ParseResult parseJson(byte[] bytes) throws IOException {
        JsonNode root = objectMapper.readTree(bytes);
        if (root == null || !root.isArray()) throw new IOException("JSON question bank must be an array of question objects.");

        List<ParsedQuestion> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int rowNum = 0;
        for (JsonNode node : root) {
            rowNum++;
            try {
                out.add(fromJson(node, rowNum, warnings));
            } catch (IllegalArgumentException e) {
                warnings.add("Row " + rowNum + ": " + e.getMessage() + " — skipped.");
            }
        }
        return new ParseResult(out, warnings);
    }

    private ParsedQuestion fromJson(JsonNode n, int row, List<String> warnings) {
        QuestionType type = type(text(n, "type"));
        AssessmentQuestion q = base(type, text(n, "skill"), text(n, "difficulty"));
        if (n.path("points").isInt()) q.setPoints(n.path("points").asInt());
        if (n.path("time_estimate_sec").isInt()) q.setTimeEstimateSec(n.path("time_estimate_sec").asInt());
        q.setTitle(text(n, "title"));
        Map<String, String> naive = Map.of();

        switch (type) {
            case MCQ, MSQ -> {
                q.setPrompt(required(firstNonBlank(text(n, "question"), text(n, "prompt")), "question text"));
                q.setOptions(n.path("options").isArray() ? strings(n.path("options")) : splitPipe(text(n, "options")));
                q.setCorrectIndices(correctIndices(n, q.getOptions()));
                q.setExplanation(text(n, "explanation"));
            }
            case SCENARIO -> {
                q.setPrompt(required(firstNonBlank(text(n, "prompt"), text(n, "question")), "prompt"));
                q.setModelAnswer(text(n, "model_answer"));
                q.setKeyPoints(n.path("key_points").isArray() ? strings(n.path("key_points")) : splitPipe(text(n, "key_points")));
            }
            case LOGIC -> {
                q.setPrompt(required(firstNonBlank(text(n, "prompt"), text(n, "question")), "prompt"));
                q.setModelAnswer(firstNonBlank(text(n, "answer"), text(n, "model_answer")));
                q.setExplanation(text(n, "explanation"));
            }
            case CODE_WRITE -> {
                q.setPrompt(required(firstNonBlank(text(n, "statement"), text(n, "prompt")), "statement"));
                q.setConstraints(text(n, "constraints"));
                q.setInputFormat(text(n, "input_format"));
                q.setOutputFormat(text(n, "output_format"));
                q.setReferenceSolution(codeMap(n.path("reference_solution")));
                q.setStarterCode(codeMap(n.path("starter_code")));
                naive = codeMap(n.path("naive_solution"));
                q.setLanguages(languages(n, q.getReferenceSolution()));
                addTests(q, n.path("tests"));
                q.setRubric(rubric(n.path("rubric")));
                q.setExpectedComplexity(text(n, "expected_complexity"));
            }
            case CODE_DEBUG -> {
                q.setPrompt(required(firstNonBlank(text(n, "statement"), text(n, "prompt")), "statement"));
                q.setBuggyCode(codeMap(n.path("buggy_code")));
                q.setStarterCode(new LinkedHashMap<>(q.getBuggyCode()));
                q.setReferenceSolution(codeMap(n.has("fixed_code") ? n.path("fixed_code") : n.path("reference_solution")));
                q.setBugDescriptions(strings(n.path("bug_descriptions")));
                q.setLanguages(languages(n, q.getBuggyCode()));
                addTests(q, n.path("tests"));
            }
            case CODE_OUTPUT -> {
                q.setPrompt(firstNonBlank(text(n, "prompt"), "What is the exact output of this program?"));
                q.setStarterCode(codeMap(n.has("code") ? n.path("code") : n.path("starter_code")));
                q.setLanguages(languages(n, q.getStarterCode()));
            }
        }
        return new ParsedQuestion(row, normalizeLevel(text(n, "level")),
                roleFamilies(n.path("role_families").isArray() ? strings(n.path("role_families"))
                        : splitPipe(text(n, "role_families")), row, warnings),
                q, naive);
    }

    // ── CSV / XLSX (theory and written types only) ──────────────────────

    private ParseResult parseCsv(byte[] bytes) throws IOException {
        List<String[]> lines = readCsvLines(new String(bytes, StandardCharsets.UTF_8));
        if (lines.isEmpty()) return new ParseResult(List.of(), List.of("File is empty."));

        String[] headers = lowercase(lines.get(0));
        List<ParsedQuestion> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i);
            if (cells.length == 1 && cells[0].isBlank()) continue;
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < headers.length && c < cells.length; c++) row.put(headers[c], cells[c]);
            parseFlatRow(row, i + 1, warnings).ifPresent(out::add);
        }
        return new ParseResult(out, warnings);
    }

    private ParseResult parseXlsx(byte[] bytes) throws IOException {
        List<ParsedQuestion> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(bytes); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                return new ParseResult(List.of(), List.of("Sheet is empty."));
            }
            DataFormatter formatter = new DataFormatter();
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) headers.add(formatter.formatCellValue(cell).strip().toLowerCase(Locale.ROOT));

            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, String> map = new HashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    map.put(headers.get(c), cell == null ? "" : formatter.formatCellValue(cell));
                }
                parseFlatRow(map, r + 1, warnings).ifPresent(out::add);
            }
        }
        return new ParseResult(out, warnings);
    }

    private Optional<ParsedQuestion> parseFlatRow(Map<String, String> row, int rowNum, List<String> warnings) {
        QuestionType type;
        try {
            type = type(row.get("type"));
        } catch (IllegalArgumentException e) {
            warnings.add("Row " + rowNum + ": " + e.getMessage() + " — skipped.");
            return Optional.empty();
        }
        if (type.isCode()) {
            warnings.add("Row " + rowNum + ": " + type + " questions carry code and tests, so they need the JSON format "
                    + "(see the sample file) — skipped.");
            return Optional.empty();
        }
        String prompt = firstNonBlank(row.get("question"), row.get("prompt"));
        if (prompt == null || prompt.isBlank()) {
            warnings.add("Row " + rowNum + ": missing question text — skipped.");
            return Optional.empty();
        }

        AssessmentQuestion q = base(type, orNull(row.get("skill")), orNull(row.get("difficulty")));
        q.setPrompt(prompt.strip());
        switch (type) {
            case MCQ, MSQ -> {
                List<String> options = splitPipe(row.get("options"));
                if (options.isEmpty()) {
                    warnings.add("Row " + rowNum + ": " + type + " question has no options — skipped.");
                    return Optional.empty();
                }
                q.setOptions(options);
                q.setCorrectIndices(parseCorrectIndices(row.get("correct_index"), options));
                q.setExplanation(orNull(row.get("explanation")));
            }
            case SCENARIO -> {
                q.setModelAnswer(orNull(row.get("model_answer")));
                q.setKeyPoints(splitPipe(row.get("key_points")));
            }
            case LOGIC -> {
                q.setModelAnswer(orNull(firstNonBlank(row.get("answer"), row.get("model_answer"))));
                q.setExplanation(orNull(row.get("explanation")));
            }
            default -> { }
        }
        return Optional.of(new ParsedQuestion(rowNum, normalizeLevel(row.get("level")),
                roleFamilies(splitPipe(row.get("role_families")), rowNum, warnings), q, Map.of()));
    }

    // ── shared helpers ──────────────────────────────────────────────────

    private static QuestionType type(String raw) {
        String t = raw == null ? "" : raw.strip();
        try {
            return QuestionType.fromBankType(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown type \"" + t + "\"");
        }
    }

    private static AssessmentQuestion base(QuestionType type, String skill, String difficulty) {
        AssessmentQuestion q = new AssessmentQuestion();
        q.setType(type);
        q.setSkill(skill == null || skill.isBlank() ? null : skill.strip());
        q.setDifficulty(difficulty == null || difficulty.isBlank() ? null : difficulty.strip().toLowerCase(Locale.ROOT));
        q.setOrigin("BANK");
        return q;
    }

    private static List<String> roleFamilies(List<String> raw, int row, List<String> warnings) {
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            String f = r.strip().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                RoleFamily.valueOf(f);
                if (!out.contains(f)) out.add(f);
            } catch (IllegalArgumentException e) {
                warnings.add("Row " + row + ": unknown role family \"" + r + "\" ignored.");
            }
        }
        return out;
    }

    /** {"java": "...", "python": "..."} or [{"language": "java", "code": "..."}]. */
    private static Map<String, String> codeMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> put(out, e.getKey(), e.getValue().asText("")));
        } else if (node.isArray()) {
            for (JsonNode entry : node) put(out, entry.path("language").asText(""), entry.path("code").asText(""));
        }
        return out;
    }

    private static void put(Map<String, String> out, String language, String code) {
        List<String> lang = RoleProfileRules.normaliseLanguages(List.of(language), true);
        if (!lang.isEmpty() && code != null && !code.isBlank()) out.putIfAbsent(lang.get(0), code);
    }

    private static List<String> languages(JsonNode n, Map<String, String> fallbackFrom) {
        List<String> declared = RoleProfileRules.normaliseLanguages(strings(n.path("languages")), true);
        return new ArrayList<>(declared.isEmpty() ? fallbackFrom.keySet() : declared);
    }

    private static void addTests(AssessmentQuestion q, JsonNode tests) {
        for (JsonNode t : tests) {
            QuestionTestCase tc = new QuestionTestCase();
            tc.setInput(t.path("input").asText(""));
            tc.setExpectedOutput(t.path("expected_output").asText(""));
            tc.setVisible(t.path("visible").asBoolean(false));
            TestCaseCategory category;
            try {
                category = TestCaseCategory.valueOf(t.path("category").asText("BASIC").strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                category = TestCaseCategory.BASIC;
            }
            tc.setCategory(category);
            tc.setWeight(t.path("weight").isNumber() ? t.path("weight").decimalValue()
                    : category == TestCaseCategory.BASIC ? BigDecimal.ONE : new BigDecimal("1.5"));
            if (t.path("float_tolerance").isNumber()) tc.setFloatTolerance(t.path("float_tolerance").decimalValue());
            q.addTestCase(tc);
        }
    }

    private static List<RubricCriterion> rubric(JsonNode node) {
        List<RubricCriterion> out = new ArrayList<>();
        for (JsonNode r : node) {
            String criterion = r.path("criterion").asText("").strip();
            if (!criterion.isEmpty() && r.path("weight").asInt(0) > 0) {
                out.add(new RubricCriterion(criterion, r.path("weight").asInt(), r.path("description").asText("")));
            }
        }
        return out.isEmpty() ? new ArrayList<>(RubricCriterion.CODE_WRITE_DEFAULT) : out;
    }

    private static List<Integer> correctIndices(JsonNode n, List<String> options) {
        JsonNode indices = n.has("correct_indices") ? n.path("correct_indices") : n.path("correct_index");
        if (indices.isArray()) {
            List<Integer> out = new ArrayList<>();
            for (JsonNode i : indices) {
                Integer idx = toIndex(i.asText(""), options.size());
                if (idx != null) out.add(idx);
            }
            return out;
        }
        return parseCorrectIndices(indices.isMissingNode() || indices.isNull() ? null : indices.asText(), options);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : array) {
            String t = s.asText("").strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isValueNode() && !v.isNull() ? v.asText() : null;
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + what);
        return value.strip();
    }

    /** Minimal RFC4180-ish CSV reader — quoted fields may contain commas and newlines. */
    private static List<String[]> readCsvLines(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else inQuotes = false;
                } else field.append(c);
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',') { fields.add(field.toString()); field.setLength(0); }
                else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    fields.add(field.toString());
                    rows.add(fields.toArray(new String[0]));
                    fields.clear();
                    field.setLength(0);
                } else field.append(c);
            }
        }
        if (field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            rows.add(fields.toArray(new String[0]));
        }
        return rows;
    }

    private static String[] lowercase(String[] arr) {
        String[] out = new String[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = arr[i].strip().toLowerCase(Locale.ROOT);
        return out;
    }

    private static List<String> splitPipe(String raw) {
        if (raw == null || raw.isBlank()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\\|")) {
            String t = s.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** One or more 0-based indices or letters (A/B/C...), comma- or pipe-separated. */
    private static List<Integer> parseCorrectIndices(String raw, List<String> options) {
        if (raw == null || raw.isBlank()) return new ArrayList<>();
        List<Integer> out = new ArrayList<>();
        for (String token : raw.split("[,|]")) {
            String t = token.strip();
            if (t.isEmpty()) continue;
            Integer idx = toIndex(t, options.size());
            if (idx != null) out.add(idx);
        }
        return out;
    }

    private static Integer toIndex(String token, int optionCount) {
        try {
            int n = Integer.parseInt(token.strip());
            return (n >= 0 && n < optionCount) ? n : null;
        } catch (NumberFormatException ignored) {
            String t = token.strip();
            if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                int idx = Character.toUpperCase(t.charAt(0)) - 'A';
                return (idx >= 0 && idx < optionCount) ? idx : null;
            }
            return null;
        }
    }

    private static String normalizeLevel(String raw) {
        if (raw == null) return null;
        String t = raw.strip().toUpperCase(Locale.ROOT);
        return (t.equals("L1") || t.equals("L2") || t.equals("L3")) ? t : null;
    }

    private static String orNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }
}
