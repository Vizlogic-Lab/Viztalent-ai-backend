package com.smartstaff.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** A minimal HTTP server standing in for Google's Gemini API and Twilio's REST
 *  API in tests (the app's base URLs are configuration — see GeminiClient /
 *  TwilioClient). Built on the JDK's own com.sun.net.httpserver, so no extra
 *  dependency.
 *
 *  Rules are matched by HTTP method + path prefix; when several match, the
 *  most recently registered wins, so a test can override a default. A request
 *  matching no rule gets a loud 404 rather than a silent success. Every
 *  request is recorded so tests can assert on what the app actually sent. */
public final class StubServer {

    public record RecordedRequest(String method, String path, String query, String body, String authorization) {}

    public record StubResponse(int status, String body) {
        public static StubResponse json(int status, String body) {
            return new StubResponse(status, body);
        }
    }

    private record Rule(String method, String pathPrefix, Function<RecordedRequest, StubResponse> handler) {}

    private final HttpServer server;
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    private StubServer(HttpServer server) {
        this.server = server;
    }

    public static StubServer start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubServer stub = new StubServer(http);
            http.createContext("/", stub::handle);
            http.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "stub-server");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the stub server", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respond(String method, String pathPrefix, int status, String body) {
        on(method, pathPrefix, req -> StubResponse.json(status, body));
    }

    public void on(String method, String pathPrefix, Function<RecordedRequest, StubResponse> handler) {
        rules.add(new Rule(method, pathPrefix, handler));
    }

    /** Requests received so far whose path starts with `pathPrefix`. */
    public List<RecordedRequest> requests(String pathPrefix) {
        List<RecordedRequest> matching = new ArrayList<>();
        for (RecordedRequest r : requests) {
            if (r.path().startsWith(pathPrefix)) matching.add(r);
        }
        return matching;
    }

    /** Forget every rule and recorded request — call between tests. */
    public void reset() {
        rules.clear();
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RecordedRequest recorded = new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                body,
                exchange.getRequestHeaders().getFirst("Authorization"));
        requests.add(recorded);

        StubResponse response = null;
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule rule = rules.get(i);
            if (rule.method().equalsIgnoreCase(recorded.method()) && recorded.path().startsWith(rule.pathPrefix())) {
                response = rule.handler().apply(recorded);
                break;
            }
        }
        if (response == null) {
            response = StubResponse.json(404, "{\"error\":\"no stub registered for " + recorded.method() + " " + recorded.path() + "\"}");
        }

        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
