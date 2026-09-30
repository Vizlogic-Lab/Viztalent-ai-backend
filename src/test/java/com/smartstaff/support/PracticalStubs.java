package com.smartstaff.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartstaff.support.StubServer.RecordedRequest;
import com.smartstaff.support.StubServer.StubResponse;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A fake Gemini that drafts practical questions and a fake Piston that
 *  "runs" them, for the question-generation tests.
 *
 *  The fake programs are markers, not code: REFERENCE/FIXED print the sum of
 *  the numbers on stdin, NAIVE prints 0, BUGGY/BADREF print the sum + 1,
 *  OUTPUT prints 42. Tests use "a b" -> a+b, so the reference passes, the
 *  naive and buggy code fail the non-zero hidden tests. */
public final class PracticalStubs {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TARGET = Pattern.compile("Target skill: \"([^\"]+)\"");

    public enum Variant { VALID, BAD_REFERENCE, TWO_ANSWER_MCQ, WRONG_SKILL }

    private PracticalStubs() {}

    // ── Piston ──────────────────────────────────────────────────────────

    public static void fakePiston(StubServer stub) {
        stub.respond("GET", "/api/v2/runtimes", 200, Fixtures.pistonRuntimes());
        stub.on("POST", "/api/v2/execute", req -> StubResponse.json(200, Fixtures.pistonRun(runFake(req))));
    }

    /** stdout the fake program would print for this execute request. */
    public static String runFake(RecordedRequest req) {
        JsonNode body = read(req.body());
        String code = body.path("files").path(0).path("content").asText("");
        long sum = 0;
        for (String token : body.path("stdin").asText("").trim().split("\\s+")) {
            if (!token.isEmpty()) sum += Long.parseLong(token);
        }
        if (code.contains("REFERENCE") || code.contains("FIXED")) return sum + "\n";
        if (code.contains("NAIVE")) return "0\n";
        if (code.contains("BUGGY") || code.contains("BADREF")) return (sum + 1) + "\n";
        if (code.contains("OUTPUT")) return "42\n";
        return "hello\n";
    }

    // ── Gemini ──────────────────────────────────────────────────────────

    /** Answers every drafting call; `variants` picks a bad draft per question type. */
    public static void fakeGemini(StubServer stub, Map<String, Variant> variants) {
        stub.on("POST", "/v1beta/models/", draftResponder(variants));
    }

    public static Function<RecordedRequest, StubResponse> draftResponder(Map<String, Variant> variants) {
        return req -> {
            // Grading calls (F9) have no "skill" in their schema; drafting calls always do.
            JsonNode props = read(req.body()).path("generationConfig").path("responseSchema").path("properties");
            if (!props.has("skill")) {
                return StubResponse.json(200, Fixtures.geminiText(props.has("criteria") ? rubricGrade() : scenarioGrade()));
            }
            String type = typeOf(req);
            String draft = draft(type, targetSkill(req), variants.getOrDefault(type, Variant.VALID));
            return StubResponse.json(200, Fixtures.geminiText(draft));
        };
    }

    /** Fake AI grade: full marks for every CODE_WRITE rubric criterion (scores
     *  are clamped to each criterion's weight, so a big number = full). */
    static String rubricGrade() {
        ArrayNode criteria = JSON.createArrayNode();
        for (String c : new String[] {"approach", "complexity", "edge_cases", "readability"}) {
            criteria.addObject().put("criterion", c).put("score", 100).put("note", "solid");
        }
        return JSON.createObjectNode().set("criteria", criteria).toString();
    }

    /** Fake AI grade: every key point (indices 1..12) covered. */
    static String scenarioGrade() {
        ArrayNode points = JSON.createArrayNode();
        for (int i = 1; i <= 12; i++) points.addObject().put("index", i).put("covered", true).put("note", "");
        return JSON.createObjectNode().set("key_points", points).toString();
    }

    public static String typeOf(RecordedRequest req) {
        JsonNode props = read(req.body()).path("generationConfig").path("responseSchema").path("properties");
        if (props.has("naive_solution")) return "CODE_WRITE";
        if (props.has("buggy_code")) return "CODE_DEBUG";
        if (props.has("code")) return "CODE_OUTPUT";
        if (props.has("key_points")) return "SCENARIO";
        if (props.has("answer")) return "LOGIC";
        return promptOf(req).contains("multiple-select") ? "MSQ" : "MCQ";
    }

    public static String promptOf(RecordedRequest req) {
        return read(req.body()).path("contents").path(0).path("parts").path(0).path("text").asText("");
    }

    static String targetSkill(RecordedRequest req) {
        Matcher m = TARGET.matcher(promptOf(req));
        return m.find() ? m.group(1) : "java";
    }

    public static String draft(String type, String skill, Variant variant) {
        ObjectNode o = JSON.createObjectNode();
        o.put("skill", variant == Variant.WRONG_SKILL ? "cobol" : skill);
        switch (type) {
            case "MCQ", "MSQ" -> {
                o.put("question", "Which statement about " + skill + " is right?");
                o.putArray("options").add("Alpha").add("Beta").add("Gamma").add("Delta");
                ArrayNode correct = o.putArray("correct_indices");
                if (type.equals("MSQ") || variant == Variant.TWO_ANSWER_MCQ) correct.add(0).add(2);
                else correct.add(1);
                o.put("explanation", "Because of how " + skill + " works.");
            }
            case "CODE_WRITE" -> {
                o.put("title", "Sum of two numbers");
                o.put("statement", "Read two integers and print their sum.");
                o.put("constraints", "-10^9 <= a, b <= 10^9");
                o.put("input_format", "a b");
                o.put("output_format", "a + b");
                code(o, "starter_code", "// STARTER");
                code(o, "reference_solution", variant == Variant.BAD_REFERENCE ? "// BADREF" : "// REFERENCE");
                code(o, "naive_solution", "// NAIVE");
                ArrayNode tests = o.putArray("tests");
                test(tests, "1 2", "3", true, "BASIC");
                test(tests, "3 4", "7", true, "BASIC");
                test(tests, "10 20", "30", true, "BASIC");
                test(tests, "0 0", "0", false, "EDGE");
                test(tests, "-5 5", "0", false, "EDGE");
                test(tests, "-3 -4", "-7", false, "EDGE");
                test(tests, "99 1", "100", false, "EDGE");
                test(tests, "7 8", "15", false, "BASIC");
                test(tests, "100 200", "300", false, "BASIC");
                test(tests, "123456 654321", "777777", false, "LARGE");
                test(tests, "500000000 500000000", "1000000000", false, "LARGE");
                ArrayNode rubric = o.putArray("rubric");
                rubric.addObject().put("criterion", "approach").put("description", "Adds directly.");
                o.put("expected_complexity", "O(1)");
            }
            case "CODE_DEBUG" -> {
                o.put("title", "Fix the total");
                o.put("statement", "This program should print the sum of two integers but has a bug.");
                code(o, "buggy_code", "// BUGGY");
                code(o, "fixed_code", "// FIXED");
                o.putArray("bug_descriptions").add("Adds one too many.");
                ArrayNode tests = o.putArray("tests");
                test(tests, "1 2", "3", true, "BASIC");
                test(tests, "2 2", "4", true, "BASIC");
                test(tests, "0 0", "0", false, "EDGE");
                test(tests, "5 6", "11", false, "BASIC");
                test(tests, "-1 -1", "-2", false, "EDGE");
                test(tests, "1000 1000", "2000", false, "LARGE");
            }
            case "CODE_OUTPUT" -> {
                o.put("title", "Read carefully");
                code(o, "code", "// OUTPUT");
                o.put("expected_output", "41");
            }
            case "SCENARIO" -> {
                o.put("prompt", "Your " + skill + " service times out under load. What do you check first?");
                o.put("model_answer", "Check metrics, then thread pools, connection pools, slow queries and timeouts.");
                o.putArray("key_points").add("Look at latency metrics").add("Check connection pool")
                        .add("Find slow queries").add("Review timeouts").add("Load test the fix");
            }
            case "LOGIC" -> {
                o.put("prompt", "A queue gets 3 jobs a minute and serves 2. How many are waiting after 12 minutes?");
                o.put("answer", "12");
                o.put("explanation", "It grows by one per minute.");
            }
            default -> throw new IllegalArgumentException(type);
        }
        return o.toString();
    }

    private static void code(ObjectNode o, String field, String code) {
        o.putArray(field).addObject().put("language", "java").put("code", code);
    }

    private static void test(ArrayNode tests, String input, String expected, boolean visible, String category) {
        tests.addObject().put("input", input).put("expected_output", expected).put("visible", visible).put("category", category);
    }

    private static JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
