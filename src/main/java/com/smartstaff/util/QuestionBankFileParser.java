package com.smartstaff.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Parses the three accepted question-bank formats (Settings.jsx: .json,
 *  .csv, .xlsx) into a common row shape, then into ParsedQuestion — a bad
 *  row is skipped with a warning rather than failing the whole upload
 *  (matches the frontend's "N row(s) were skipped" messaging). */
@Component
public class QuestionBankFileParser {

    private static final Set<String> VALID_TYPES = Set.of("MCQ", "MSQ", "DESCRIPTIVE", "CODING");
    private final ObjectMapper objectMapper;

    public QuestionBankFileParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ParsedQuestion(String type, String level, String skill, String difficulty,
                                  String prompt, List<String> options, List<Integer> correctIndices) {}

    public record ParseResult(List<ParsedQuestion> questions, List<String> warnings) {}

    public ParseResult parse(byte[] bytes, String filename) throws IOException {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) return parseJson(bytes);
        if (lower.endsWith(".xlsx")) return parseXlsx(bytes);
        if (lower.endsWith(".csv")) return parseCsv(bytes);
        throw new IOException("Unsupported file type — use .json, .csv, or .xlsx.");
    }

    // ── JSON ─────────────────────────────────────────────────────────────

    private ParseResult parseJson(byte[] bytes) throws IOException {
        JsonNode root = objectMapper.readTree(bytes);
        if (!root.isArray()) throw new IOException("JSON question bank must be an array of question objects.");

        List<ParsedQuestion> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int rowNum = 0;
        for (JsonNode node : root) {
            rowNum++;
            Map<String, String> row = new HashMap<>();
            node.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                row.put(e.getKey().toLowerCase(Locale.ROOT), v.isArray()
                        ? asOptionsString(v)
                        : v.asText(""));
            });
            parseRow(row, rowNum, warnings).ifPresent(out::add);
        }
        return new ParseResult(out, warnings);
    }

    private static String asOptionsString(JsonNode arr) {
        List<String> parts = new ArrayList<>();
        arr.forEach(n -> parts.add(n.asText("")));
        return String.join("|", parts);
    }

    // ── CSV ──────────────────────────────────────────────────────────────

    private ParseResult parseCsv(byte[] bytes) throws IOException {
        List<String[]> lines = readCsvLines(new String(bytes, StandardCharsets.UTF_8));
        if (lines.isEmpty()) return new ParseResult(List.of(), List.of("File is empty."));

        String[] headers = lowercase(lines.get(0));
        List<ParsedQuestion> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i);
            if (cells.length == 1 && cells[0].isBlank()) continue; // trailing blank line
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < headers.length && c < cells.length; c++) row.put(headers[c], cells[c]);
            parseRow(row, i + 1, warnings).ifPresent(out::add);
        }
        return new ParseResult(out, warnings);
    }

    /** Minimal RFC4180-ish CSV reader — handles quoted fields (so question
     *  text containing commas doesn't break column alignment) without
     *  pulling in a full CSV library for one file format. */
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

    // ── XLSX ─────────────────────────────────────────────────────────────

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
                parseRow(map, r + 1, warnings).ifPresent(out::add);
            }
        }
        return new ParseResult(out, warnings);
    }

    // ── shared row → ParsedQuestion ─────────────────────────────────────

    private Optional<ParsedQuestion> parseRow(Map<String, String> row, int rowNum, List<String> warnings) {
        String type = orEmpty(row.get("type")).strip().toUpperCase(Locale.ROOT);
        String prompt = firstNonBlank(row.get("question"), row.get("prompt"));

        if (!VALID_TYPES.contains(type)) {
            warnings.add("Row " + rowNum + ": unknown type \"" + row.get("type") + "\" — skipped.");
            return Optional.empty();
        }
        if (prompt == null || prompt.isBlank()) {
            warnings.add("Row " + rowNum + ": missing question text — skipped.");
            return Optional.empty();
        }

        List<String> options = splitPipe(row.get("options"));
        List<Integer> correctIndices = parseCorrectIndices(row.get("correct_index"), options);

        if ((type.equals("MCQ") || type.equals("MSQ")) && options.isEmpty()) {
            warnings.add("Row " + rowNum + ": " + type + " question has no options — skipped.");
            return Optional.empty();
        }

        String level = normalizeLevel(row.get("level"));
        String difficulty = orNull(row.get("difficulty")) == null ? null : row.get("difficulty").strip().toUpperCase(Locale.ROOT);
        String skill = orNull(row.get("skill"));

        return Optional.of(new ParsedQuestion(type, level, skill, difficulty, prompt.strip(), options, correctIndices));
    }

    private static List<String> splitPipe(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\\|")) {
            String t = s.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Accepts one or more 0-based indices or letters (A/B/C...), comma- or
     *  pipe-separated — MCQ has one, MSQ can have several. */
    private static List<Integer> parseCorrectIndices(String raw, List<String> options) {
        if (raw == null || raw.isBlank()) return List.of();
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
            int n = Integer.parseInt(token);
            return (n >= 0 && n < optionCount) ? n : null;
        } catch (NumberFormatException ignored) {
            if (token.length() == 1 && Character.isLetter(token.charAt(0))) {
                int idx = Character.toUpperCase(token.charAt(0)) - 'A';
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

    private static String orEmpty(String s) { return s == null ? "" : s; }
    private static String orNull(String s) { return (s == null || s.isBlank()) ? null : s.strip(); }
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }
}
