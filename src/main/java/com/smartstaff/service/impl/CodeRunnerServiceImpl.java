package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.service.CodeRunnerService;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.OutputComparator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;
import java.util.concurrent.*;

import static com.smartstaff.service.CodeRunnerService.RunStatus.*;
import static com.smartstaff.service.CodeRunnerService.TestStatus.*;

/** Piston-backed runner.
 *
 *  Concurrency: a fair semaphore caps executions in flight at
 *  app.runner.max-concurrency (default 8) across the whole app. A batch
 *  waits up to app.runner.queue-wait-ms (default 30 s) for its first slot and
 *  otherwise reports RUNNER_BUSY. The first test doubles as the compile check:
 *  a compile error marks every test COMPILE_ERROR without running the rest.
 *  The remaining tests run in parallel (virtual threads) until the batch wall
 *  clock runs out; anything unfinished then is a TIMEOUT.
 *
 *  Sandbox limits (no network, memory, output size) are enforced by the
 *  Piston container's own config — see docker-compose.yml. */
@Service
public class CodeRunnerServiceImpl implements CodeRunnerService {

    private static final Logger log = LoggerFactory.getLogger(CodeRunnerServiceImpl.class);
    private static final int CAPTURE_LIMIT = 2048;

    private static final Map<String, List<String>> PISTON_NAMES = Map.of(
            "java", List.of("java"),
            "python", List.of("python", "python3", "py"),
            "javascript", List.of("javascript", "js", "node-js", "node-javascript"),
            "cpp", List.of("c++", "cpp", "g++"));
    private static final Map<String, String> FILE_NAMES = Map.of(
            "java", "Main.java", "python", "main.py", "javascript", "main.js", "cpp", "main.cpp");
    static final Map<String, String> HELLO_WORLD = Map.of(
            "java", "public class Main { public static void main(String[] a) { System.out.println(\"hello\"); } }",
            "python", "print(\"hello\")",
            "javascript", "console.log(\"hello\")",
            "cpp", "#include <iostream>\nint main() { std::cout << \"hello\" << std::endl; return 0; }");

    private final SettingsService settings;
    private final ObjectMapper json;
    private final RestClient http;
    private final Semaphore permits;
    private final long queueWaitMs;
    private final long runtimesCacheMs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile CachedRuntimes cache;

    record Runtime(String language, String version) {}

    private record CachedRuntimes(String url, long fetchedAtMs, Map<String, Runtime> byName) {}

    private record Outcome(TestResult result, String compileError) {}

    private static final class RunnerException extends Exception {
        RunnerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public CodeRunnerServiceImpl(SettingsService settings, ObjectMapper json,
                                 @Value("${app.runner.max-concurrency:8}") int maxConcurrency,
                                 @Value("${app.runner.queue-wait-ms:30000}") long queueWaitMs,
                                 @Value("${app.runner.runtimes-cache-ms:300000}") long runtimesCacheMs) {
        this.settings = settings;
        this.json = json;
        this.permits = new Semaphore(maxConcurrency, true);
        this.queueWaitMs = queueWaitMs;
        this.runtimesCacheMs = runtimesCacheMs;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(30_000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public RunResult runTests(String language, String code, List<TestInput> tests, Limits limits) {
        String lang = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        String base = pistonBase();
        if (base == null) return failed(RUNNER_UNAVAILABLE, "The code runner isn't configured yet.", lang, null);

        Runtime runtime;
        try {
            runtime = runtimes(base).get(lang);
        } catch (Exception e) {
            log.warn("Piston runtimes unavailable at {}: {}", base, e.toString());
            return failed(RUNNER_UNAVAILABLE, "The code runner can't be reached right now.", lang, null);
        }
        if (runtime == null) return failed(UNSUPPORTED_LANGUAGE, "Language '" + language + "' isn't available.", lang, null);
        if (tests.isEmpty()) return new RunResult(OK, null, lang, runtime.version(), null, List.of());

        try {
            if (!permits.tryAcquire(queueWaitMs, TimeUnit.MILLISECONDS)) {
                return failed(RUNNER_BUSY, "The code runner is busy — please try again in a moment.", lang, runtime.version());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(RUNNER_BUSY, "Interrupted while waiting for the code runner.", lang, runtime.version());
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.batchWallClockMs());
        Outcome first;
        try {
            first = execute(base, runtime, lang, code, tests.get(0), limits);
        } catch (RunnerException e) {
            log.warn("Piston execute failed at {}: {}", base, e.getMessage());
            return failed(RUNNER_UNAVAILABLE, "The code runner can't be reached right now.", lang, runtime.version());
        } finally {
            permits.release();
        }

        if (first.compileError() != null) {
            List<TestResult> all = tests.stream()
                    .map(t -> new TestResult(t.seq(), COMPILE_ERROR, "", null, null, null))
                    .toList();
            return new RunResult(OK, null, lang, runtime.version(), first.compileError(), all);
        }

        List<TestResult> results = new ArrayList<>(tests.size());
        results.add(first.result());
        List<Future<TestResult>> pending = new ArrayList<>();
        for (TestInput test : tests.subList(1, tests.size())) {
            pending.add(executor.submit(() -> runOne(base, runtime, lang, code, test, limits, deadline)));
        }
        for (int i = 0; i < pending.size(); i++) {
            TestInput test = tests.get(i + 1);
            Future<TestResult> future = pending.get(i);
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                results.add(future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                results.add(new TestResult(test.seq(), TIMEOUT, "", "Time limit for this run was reached.", null, null));
            } catch (ExecutionException e) {
                results.add(new TestResult(test.seq(), RUNTIME_ERROR, "", "The code runner failed on this test.", null, null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.forEach(f -> f.cancel(true));
                results.add(new TestResult(test.seq(), TIMEOUT, "", "Interrupted.", null, null));
            }
        }
        return new RunResult(OK, null, lang, runtime.version(), null, List.copyOf(results));
    }

    private TestResult runOne(String base, Runtime runtime, String lang, String code, TestInput test,
                              Limits limits, long deadline) {
        long remaining = deadline - System.nanoTime();
        try {
            if (remaining <= 0 || !permits.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                return new TestResult(test.seq(), TIMEOUT, "", "Time limit for this run was reached.", null, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(test.seq(), TIMEOUT, "", "Interrupted.", null, null);
        }
        try {
            return execute(base, runtime, lang, code, test, limits).result();
        } catch (RunnerException e) {
            return new TestResult(test.seq(), RUNTIME_ERROR, "", "The code runner failed on this test.", null, null);
        } finally {
            permits.release();
        }
    }

    private Outcome execute(String base, Runtime runtime, String lang, String code, TestInput test, Limits limits)
            throws RunnerException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", runtime.language());
        body.put("version", runtime.version());
        body.put("files", List.of(Map.of("name", FILE_NAMES.get(lang), "content", code == null ? "" : code)));
        body.put("stdin", test.input() == null ? "" : test.input());
        body.put("args", List.of());
        body.put("compile_timeout", limits.compileTimeoutMs());
        body.put("run_timeout", limits.runTimeoutMs());
        body.put("run_memory_limit", limits.memoryBytes());

        long started = System.nanoTime();
        JsonNode root;
        try {
            String response = http.post().uri(base + "/execute").contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);
            root = json.readTree(response);
        } catch (RestClientResponseException e) {
            throw new RunnerException("Piston returned " + e.getStatusCode() + ": " + truncate(e.getResponseBodyAsString()), e);
        } catch (Exception e) {
            throw new RunnerException(e.toString(), e);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        JsonNode compile = root.path("compile");
        boolean compileFailed = compile.isObject()
                && ((compile.path("code").isNumber() && compile.path("code").asInt() != 0)
                    || text(compile.path("status")) != null || text(compile.path("signal")) != null);
        if (compileFailed) {
            String err = firstNonBlank(text(compile.path("stderr")), text(compile.path("output")), text(compile.path("message")));
            if ("TO".equals(text(compile.path("status")))) err = "Compilation timed out.";
            return new Outcome(null, truncate(err == null ? "Compilation failed." : err));
        }

        JsonNode run = root.path("run");
        String status = text(run.path("status"));
        String stdout = text(run.path("stdout"));
        String stderr = text(run.path("stderr"));
        String signal = text(run.path("signal"));
        Integer exitCode = run.path("code").isNumber() ? run.path("code").asInt() : null;
        Long timeMs = run.path("wall_time").isNumber() ? Long.valueOf(run.path("wall_time").asLong()) : Long.valueOf(elapsedMs);
        Long memory = run.path("memory").isNumber() ? Long.valueOf(run.path("memory").asLong()) : null;

        TestStatus result;
        if ("TO".equals(status) || (status == null && "SIGKILL".equals(signal))) {
            result = TIMEOUT;
        } else if ("OL".equals(status) || "EL".equals(status)) {
            result = RUNTIME_ERROR;
            stderr = "Output limit exceeded.";
        } else if (status != null || signal != null || (exitCode != null && exitCode != 0)) {
            result = RUNTIME_ERROR;
            if (stderr == null || stderr.isBlank()) stderr = text(run.path("message"));
        } else {
            result = OutputComparator.matches(test.expectedOutput(), stdout, test.floatTolerance()) ? PASSED : FAILED;
        }
        return new Outcome(new TestResult(test.seq(), result, truncate(stdout == null ? "" : stdout),
                truncate(stderr), timeMs, memory), null);
    }

    @Override
    public Map<String, String> availableLanguages() {
        String base = pistonBase();
        if (base == null) return Map.of();
        try {
            Map<String, String> out = new LinkedHashMap<>();
            runtimes(base).forEach((ours, rt) -> out.put(ours, rt.version()));
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Override
    public List<LanguageCheck> selfTest() {
        cache = null;
        List<LanguageCheck> checks = new ArrayList<>();
        for (String lang : LANGUAGES) {
            RunResult r = runTests(lang, HELLO_WORLD.get(lang), List.of(new TestInput(0, "", "hello")), Limits.DEFAULT);
            String message;
            boolean ok = false;
            if (r.status() != OK) {
                message = r.message();
            } else if (r.compileError() != null) {
                message = "Compile error: " + r.compileError();
            } else {
                TestResult t = r.tests().get(0);
                ok = t.passed();
                message = ok ? "Works." : t.status() + (t.stderr() == null ? "" : ": " + t.stderr());
            }
            checks.add(new LanguageCheck(lang, ok, r.version(), message));
        }
        return checks;
    }

    // ── runtimes ────────────────────────────────────────────────────────

    private Map<String, Runtime> runtimes(String base) throws Exception {
        CachedRuntimes c = cache;
        long now = System.currentTimeMillis();
        if (c != null && c.url().equals(base) && now - c.fetchedAtMs() < runtimesCacheMs) return c.byName();

        JsonNode list = json.readTree(http.get().uri(base + "/runtimes").retrieve().body(String.class));
        Map<String, Runtime> byName = new LinkedHashMap<>();
        for (String ours : LANGUAGES) {
            Runtime best = null;
            for (JsonNode r : list) {
                if ("javascript".equals(ours) && "deno".equals(text(r.path("runtime")))) continue;
                Set<String> names = new HashSet<>();
                names.add(r.path("language").asText("").toLowerCase(Locale.ROOT));
                r.path("aliases").forEach(a -> names.add(a.asText("").toLowerCase(Locale.ROOT)));
                if (PISTON_NAMES.get(ours).stream().noneMatch(names::contains)) continue;
                Runtime candidate = new Runtime(r.path("language").asText(), r.path("version").asText());
                if (best == null || compareVersions(candidate.version(), best.version()) > 0) best = candidate;
            }
            if (best != null) byName.put(ours, best);
        }
        cache = new CachedRuntimes(base, now, Map.copyOf(byName));
        return byName;
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? leadingInt(x[i]) : 0;
            int q = i < y.length ? leadingInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int leadingInt(String s) {
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        return end == 0 ? 0 : Integer.parseInt(s.substring(0, Math.min(end, 9)));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private String pistonBase() {
        String url = settings.getPistonUrlOrNull();
        return url == null || url.isBlank() ? null : url.trim().replaceAll("/+$", "");
    }

    private static RunResult failed(RunStatus status, String message, String lang, String version) {
        return new RunResult(status, message, lang, version, null, List.of());
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    static String truncate(String s) {
        if (s == null || s.length() <= CAPTURE_LIMIT) return s;
        return s.substring(0, CAPTURE_LIMIT) + "\n…[truncated]";
    }
}
