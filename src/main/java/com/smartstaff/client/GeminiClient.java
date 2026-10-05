package com.smartstaff.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.ai.AiConfigService;
import com.smartstaff.ai.AiFeature;
import com.smartstaff.ai.AiUsageService;
import com.smartstaff.ai.GeminiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Gateway to Google's Gemini REST API — the single place that knows the URL
 *  shape, timeouts, retries and which model to call.
 *
 *  The model and fallback model come from {@link AiConfigService} on every call
 *  (chosen in Settings → AI provider), never from the code. Every failure is a
 *  {@link GeminiException} with a code callers can act on:
 *    429 / RESOURCE_EXHAUSTED → up to 3 tries with exponential backoff + jitter
 *        (honouring Retry-After), then the fallback model, then AI_QUOTA_EXCEEDED;
 *    5xx / network            → up to 3 tries, then the fallback, then AI_UNAVAILABLE;
 *    404                      → AI_MODEL_UNAVAILABLE at once (no retry);
 *    401 / 403                → AI_KEY_INVALID;  other 4xx → AI_REQUEST_REJECTED.
 *  Each call is counted per feature (AiUsageService), and non-critical features
 *  stop at the daily cap. The key travels only in a header and is never logged. */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // Generation of a full assessment level / interview script can take a while.
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_AFTER_MS = 30_000;

    private final RestClient restClient;
    private final String baseUrl;
    private final AiConfigService config;
    private final AiUsageService usage;
    private final ObjectMapper json;
    private final long backoffBaseMs;

    public GeminiClient(@Value("${app.gemini.base-url}") String baseUrl,
                        @Value("${app.ai.retry-base-ms:1000}") long backoffBaseMs,
                        AiConfigService config, AiUsageService usage, ObjectMapper json) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.backoffBaseMs = backoffBaseMs;
        this.config = config;
        this.usage = usage;
        this.json = json;
        this.restClient = RestClient.builder().requestFactory(requestFactory()).build();
    }

    /** The configured primary model, or null if none is chosen yet. */
    public String model() {
        return config.model();
    }

    /** POST v1beta/models/{model}:generateContent with the configured model (and
     *  fallback). Returns the raw JSON body; throws GeminiException. */
    public String generateContent(String apiKey, Map<String, Object> body, AiFeature feature) {
        String primary = config.model();
        if (primary == null) {
            throw new GeminiException(GeminiException.NOT_CONFIGURED,
                    "No AI model is chosen yet. An admin can pick one in Settings → AI provider.");
        }
        usage.checkCap(feature);
        try {
            String raw;
            try {
                raw = callWithRetries(apiKey, primary, body);
            } catch (Exhausted e) {
                String fallback = config.fallbackModel();
                if (fallback == null || fallback.equals(primary)) throw e.asException();
                log.warn("Gemini model '{}' {}; switching to the fallback model '{}'", primary, e.what(), fallback);
                raw = callWithRetries(apiKey, fallback, body);
            }
            recordTokens(feature, raw);
            return raw;
        } catch (Exhausted e) {
            usage.record(feature, false, 0, 0);
            throw e.asException();
        } catch (GeminiException e) {
            usage.record(feature, false, 0, 0);
            throw e;
        }
    }

    /** One named model only, no fallback (used by the connection test). Counted as CONNECTION_TEST. */
    public String generateContentOn(String apiKey, String model, Map<String, Object> body) {
        try {
            String raw = callWithRetries(apiKey, model, body);
            recordTokens(AiFeature.CONNECTION_TEST, raw);
            return raw;
        } catch (Exhausted e) {
            usage.record(AiFeature.CONNECTION_TEST, false, 0, 0);
            throw e.asException();
        } catch (GeminiException e) {
            usage.record(AiFeature.CONNECTION_TEST, false, 0, 0);
            throw e;
        }
    }

    /** One model: retries 429 and 5xx/network; 404 and auth errors fail at once. */
    private String callWithRetries(String apiKey, String model, Map<String, Object> body) {
        boolean quota = false;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.post()
                        .uri(baseUrl + "/v1beta/models/{model}:generateContent", model)
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if (status == 404) {
                    log.warn("Gemini model '{}' was not found (404). Choose an available model in Settings → AI provider "
                            + "(or set GEMINI_MODEL / GEMINI_FALLBACK_MODEL).", model);
                    throw new GeminiException(GeminiException.MODEL_UNAVAILABLE,
                            "The AI model '" + model + "' isn't available to this key. Choose another model in Settings → AI provider.");
                }
                if (status == 401 || status == 403) {
                    log.warn("Gemini rejected the API key ({})", status);
                    throw new GeminiException(GeminiException.KEY_INVALID,
                            "Gemini rejected the API key. Check it in Settings → AI provider.");
                }
                boolean retryable = status == 429 || e.getStatusCode().is5xxServerError();
                if (!retryable) {
                    log.warn("Gemini rejected the request ({}) for model '{}'", status, model);
                    throw new GeminiException(GeminiException.REQUEST_REJECTED,
                            "Gemini rejected the request (" + status + ").");
                }
                quota = status == 429;
                if (attempt == MAX_ATTEMPTS) break;
                long wait = backoff(attempt, e.getResponseHeaders());
                log.warn("Gemini {} for model '{}' on attempt {}/{}; retrying in {} ms", status, model, attempt, MAX_ATTEMPTS, wait);
                sleep(wait);
            } catch (ResourceAccessException e) {
                quota = false;
                if (attempt == MAX_ATTEMPTS) break;
                long wait = backoff(attempt, null);
                log.warn("Gemini unreachable for model '{}' on attempt {}/{}; retrying in {} ms", model, attempt, MAX_ATTEMPTS, wait);
                sleep(wait);
            }
        }
        throw new Exhausted(quota);
    }

    /** Exponential backoff with jitter; a Retry-After header (seconds) wins, capped at 30 s. */
    long backoff(int attempt, HttpHeaders headers) {
        Long retryAfter = retryAfterMs(headers);
        if (retryAfter != null) return Math.min(retryAfter, MAX_RETRY_AFTER_MS);
        long base = backoffBaseMs * (1L << (attempt - 1));
        return base + ThreadLocalRandom.current().nextLong(Math.max(1, backoffBaseMs / 2 + 1));
    }

    static Long retryAfterMs(HttpHeaders headers) {
        String v = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (v == null || v.isBlank()) return null;
        try {
            return Math.max(0, Long.parseLong(v.trim())) * 1000;
        } catch (NumberFormatException e) {
            return null;   // HTTP-date form: fall back to our own backoff
        }
    }

    private void recordTokens(AiFeature feature, String raw) {
        long in = 0, out = 0;
        try {
            JsonNode meta = json.readTree(raw).path("usageMetadata");
            in = meta.path("promptTokenCount").asLong(0);
            out = meta.path("candidatesTokenCount").asLong(0);
        } catch (Exception ignored) {
            // token counts are best effort
        }
        usage.record(feature, true, in, out);
    }

    /** GET v1beta/models — the models this key can use. Throws GeminiException. */
    public String listModels(String apiKey) {
        try {
            return restClient.get()
                    .uri(baseUrl + "/v1beta/models?pageSize=1000")
                    .header("x-goog-api-key", apiKey)
                    .retrieve()
                    .body(String.class);
        } catch (HttpStatusCodeException e) {
            int s = e.getStatusCode().value();
            if (s == 401 || s == 403 || s == 400) {
                throw new GeminiException(GeminiException.KEY_INVALID, "Gemini rejected the API key.");
            }
            if (s == 429) throw new GeminiException(GeminiException.QUOTA_EXCEEDED, "The AI key is out of quota (Gemini 429).");
            throw new GeminiException(GeminiException.UNAVAILABLE, "Gemini is unavailable (" + s + ").");
        } catch (ResourceAccessException e) {
            throw new GeminiException(GeminiException.UNAVAILABLE, "Couldn't reach Gemini.");
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** All tries on one model ran out (quota, overload or network). */
    private static final class Exhausted extends RuntimeException {
        private final boolean quota;

        Exhausted(boolean quota) {
            super(null, null, false, false);
            this.quota = quota;
        }

        String what() {
            return quota ? "is out of quota (429)" : "is unavailable";
        }

        GeminiException asException() {
            return quota
                    ? new GeminiException(GeminiException.QUOTA_EXCEEDED,
                    "The AI key has run out of quota (Gemini 429). Add a key with quota or a fallback model in Settings → AI provider, or try again later.")
                    : new GeminiException(GeminiException.UNAVAILABLE,
                    "The AI service is unavailable right now. Please try again in a few minutes.");
        }
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
