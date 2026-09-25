package com.smartstaff.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/** Canned payloads for the stubbed external APIs, plus a Twilio request signer.
 *  Deliberately independent of production code (the signer re-implements
 *  Twilio's documented algorithm) so tests verify the app rather than mirror it. */
public final class Fixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {}

    // ── Gemini ──────────────────────────────────────────────────────────

    /** A generateContent response whose only part is `text`. */
    public static String geminiText(String text) {
        ObjectNode part = JSON.createObjectNode().put("text", text);
        return envelope(part);
    }

    /** A generateContent response where the model chose to call a function. */
    public static String geminiFunctionCall(String functionName) {
        ObjectNode call = JSON.createObjectNode();
        call.putObject("functionCall").put("name", functionName).putObject("args");
        return envelope(call);
    }

    private static String envelope(ObjectNode part) {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("candidates").addObject().putObject("content").putArray("parts").add(part);
        return root.toString();
    }

    /** The JSON *text* Gemini is asked to return for an assessment level: an array of questions. */
    public static String assessmentQuestionsJson(int count) {
        ArrayNode arr = JSON.createArrayNode();
        for (int i = 1; i <= count; i++) {
            ObjectNode q = arr.addObject();
            boolean multi = i % 3 == 0;
            q.put("type", multi ? "MSQ" : "MCQ");
            q.put("question", "Generated question " + i + "?");
            q.putArray("options").add("Alpha").add("Beta").add("Gamma").add("Delta");
            if (multi) q.putArray("correct_indices").add(0).add(2);
            else q.putArray("correct_indices").add(1);
            q.put("skill", "java");
            q.put("difficulty", "medium");
        }
        return arr.toString();
    }

    /** The JSON *text* Gemini is asked to return for interview prep. */
    public static String interviewJson(int questionCount) {
        ObjectNode obj = JSON.createObjectNode();
        obj.put("intro", "Hello, and welcome to your short AI interview.");
        obj.put("outro", "Thank you — our team will be in touch.");
        ArrayNode qs = obj.putArray("questions");
        for (int i = 1; i <= questionCount; i++) {
            ObjectNode q = qs.addObject();
            q.put("category", i == 1 ? "Background" : "Technical");
            if (i == 1) q.putNull("skill"); else q.put("skill", "java");
            q.put("question", "Interview question number " + i + "?");
        }
        return obj.toString();
    }

    // ── Twilio ──────────────────────────────────────────────────────────

    /** Twilio's documented signature: base64(HMAC-SHA1(token, url + sorted key+value pairs)). */
    public static String twilioSignature(String authToken, String fullUrl, Map<String, String> params) {
        try {
            StringBuilder data = new StringBuilder(fullUrl);
            new TreeMap<>(params).forEach((k, v) -> data.append(k).append(v));
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
