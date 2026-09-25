package com.smartstaff.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Thin gateway to Twilio's REST API (Basic auth with account SID + auth
 *  token) — same role and same reasons as {@link GeminiClient}: one place
 *  for the URL shape and timeouts, and a configurable base URL
 *  (app.twilio.api-base-url) so tests can substitute a local stub.
 *
 *  Both methods return the raw JSON body and throw
 *  org.springframework.web.client.RestClientException on any non-2xx or
 *  I/O failure. */
@Component
public class TwilioClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final String baseUrl;

    public TwilioClient(@Value("${app.twilio.api-base-url}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** GET /2010-04-01/Accounts/{sid}.json — only used to check saved credentials. */
    public String fetchAccount(String accountSid, String authToken) {
        return restClient.get()
                .uri(baseUrl + "/2010-04-01/Accounts/{sid}.json", accountSid)
                .headers(h -> h.setBasicAuth(accountSid, authToken))
                .retrieve()
                .body(String.class);
    }

    /** POST /2010-04-01/Accounts/{sid}/Calls.json (form-encoded) — places an outbound call. */
    public String createCall(String accountSid, String authToken, MultiValueMap<String, String> form) {
        return restClient.post()
                .uri(baseUrl + "/2010-04-01/Accounts/{sid}/Calls.json", accountSid)
                .headers(h -> h.setBasicAuth(accountSid, authToken))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
    }
}
