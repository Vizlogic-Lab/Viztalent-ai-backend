package com.smartstaff.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Thin gateway to Google's Gemini REST API — the single place that knows
 *  the URL shape, model, and timeouts, so AssessmentServiceImpl,
 *  InterviewServiceImpl, RecruiterChatServiceImpl and SettingsServiceImpl
 *  don't each carry their own copy.
 *
 *  The base URL and model are configuration (app.gemini.*) rather than
 *  constants so automated tests can point this at a local stub server —
 *  which is how the success paths of AI question generation, interview
 *  prep, and the chat's tool call are covered without a real API key.
 *
 *  Both methods return the raw JSON body and throw
 *  org.springframework.web.client.RestClientException on any non-2xx or
 *  I/O failure; callers decide how to degrade (each has its own contract
 *  with the frontend — see their javadocs). */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // Generation of a full assessment level / interview script can take a while.
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    // Google returns 503 UNAVAILABLE when a model is briefly overloaded ("experiencing
    // high demand"); such spikes are transient, so retry a few times before giving up.
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);

    private final RestClient restClient;
    private final String baseUrl;
    private final String model;

    public GeminiClient(
            @Value("${app.gemini.base-url}") String baseUrl,
            @Value("${app.gemini.model}") String model
    ) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.restClient = RestClient.builder().requestFactory(requestFactory()).build();
    }

    public String model() {
        return model;
    }

    /** POST v1beta/models/{model}:generateContent.
     *  Retries a handful of times on a 503/overload before propagating, so a
     *  momentary spike in Google's demand doesn't surface to the user. */
    public String generateContent(String apiKey, Map<String, Object> body) {
        HttpServerErrorException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.post()
                        .uri(baseUrl + "/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
            } catch (HttpServerErrorException e) {
                if (!isRetryable(e.getStatusCode()) || attempt == MAX_ATTEMPTS) throw e;
                last = e;
                log.warn("Gemini {} on attempt {}/{}, retrying in {}ms",
                        e.getStatusCode(), attempt, MAX_ATTEMPTS, RETRY_BACKOFF.toMillis());
                sleep(RETRY_BACKOFF.multipliedBy(attempt));
            }
        }
        throw last; // unreachable: the loop either returns or throws above
    }

    private static boolean isRetryable(HttpStatusCode status) {
        // 503 (overloaded) and 429 (rate-limited) are worth a retry; other 5xx too.
        return status.value() == 503 || status.value() == 429 || status.is5xxServerError();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** GET v1beta/models — only used to check that a saved key is accepted. */
    public String listModels(String apiKey) {
        return restClient.get()
                .uri(baseUrl + "/v1beta/models?key={key}", apiKey)
                .retrieve()
                .body(String.class);
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
