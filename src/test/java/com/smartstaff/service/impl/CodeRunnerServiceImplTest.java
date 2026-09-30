package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.service.CodeRunnerService.Limits;
import com.smartstaff.service.CodeRunnerService.RunResult;
import com.smartstaff.service.CodeRunnerService.RunStatus;
import com.smartstaff.service.CodeRunnerService.TestInput;
import com.smartstaff.service.CodeRunnerService.TestResult;
import com.smartstaff.service.CodeRunnerService.TestStatus;
import com.smartstaff.service.SettingsService;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.StubServer;
import com.smartstaff.support.StubServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CodeRunnerServiceImpl against a StubServer pretending to be Piston. */
class CodeRunnerServiceImplTest {

    private static final StubServer PISTON = StubServer.start();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUM = "a, b = map(int, input().split())\nprint(a + b)\n";

    private final SettingsService settings = mock(SettingsService.class);

    @BeforeEach
    void setUp() {
        PISTON.reset();
        when(settings.getPistonUrlOrNull()).thenReturn(PISTON.baseUrl() + "/api/v2/");
        PISTON.respond("GET", "/api/v2/runtimes", 200, Fixtures.pistonRuntimes());
    }

    private CodeRunnerServiceImpl runner(int maxConcurrency, long queueWaitMs) {
        return new CodeRunnerServiceImpl(settings, JSON, maxConcurrency, queueWaitMs, 300_000);
    }

    private CodeRunnerServiceImpl runner() {
        return runner(8, 30_000);
    }

    private static JsonNode body(StubServer.RecordedRequest req) {
        try {
            return JSON.readTree(req.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fake program that adds the two numbers on stdin. */
    private void stubAdder(long delayMs) {
        PISTON.on("POST", "/api/v2/execute", req -> {
            sleep(delayMs);
            long sum = Arrays.stream(body(req).path("stdin").asText().trim().split("\\s+")).mapToLong(Long::parseLong).sum();
            return StubResponse.json(200, Fixtures.pistonRun(sum + "\n"));
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<TestInput> tests(String... inputAndExpected) {
        return IntStream.range(0, inputAndExpected.length / 2)
                .mapToObj(i -> new TestInput(i, inputAndExpected[2 * i], inputAndExpected[2 * i + 1]))
                .toList();
    }

    @Test
    void passAndFailPerTestWithTheRightRequest() {
        stubAdder(0);
        RunResult r = runner().runTests("java", "class Main {}", tests("1 2", "3", "5 5", "10", "2 2", "5"), Limits.DEFAULT);

        assertThat(r.status()).isEqualTo(RunStatus.OK);
        assertThat(r.version()).isEqualTo("15.0.2");
        assertThat(r.tests()).extracting(TestResult::status)
                .containsExactly(TestStatus.PASSED, TestStatus.PASSED, TestStatus.FAILED);
        assertThat(r.passedCount()).isEqualTo(2);
        assertThat(r.tests().get(2).actualOutput()).isEqualTo("4\n");
        assertThat(r.tests().get(0).timeMs()).isEqualTo(37);
        assertThat(r.tests().get(0).memoryBytes()).isEqualTo(8_192_000);

        List<StubServer.RecordedRequest> calls = PISTON.requests("/api/v2/execute");
        assertThat(calls).hasSize(3);
        JsonNode sent = body(calls.get(0));
        assertThat(sent.path("language").asText()).isEqualTo("java");
        assertThat(sent.path("version").asText()).isEqualTo("15.0.2");
        assertThat(sent.path("files").path(0).path("name").asText()).isEqualTo("Main.java");
        assertThat(sent.path("files").path(0).path("content").asText()).isEqualTo("class Main {}");
        assertThat(sent.path("run_timeout").asInt()).isEqualTo(3000);
        assertThat(sent.path("compile_timeout").asInt()).isEqualTo(10000);
        assertThat(sent.path("run_memory_limit").asLong()).isEqualTo(256L * 1024 * 1024);
    }

    @Test
    void languageMappingPicksLatestVersionAndNodeOverDeno() {
        stubAdder(0);
        CodeRunnerServiceImpl runner = runner();

        assertThat(runner.runTests("python", SUM, tests("1 1", "2"), Limits.DEFAULT).version()).isEqualTo("3.12.0");
        assertThat(runner.runTests("javascript", "x", tests("1 1", "2"), Limits.DEFAULT).version()).isEqualTo("18.15.0");
        runner.runTests("cpp", "x", tests("1 1", "2"), Limits.DEFAULT);

        List<StubServer.RecordedRequest> calls = PISTON.requests("/api/v2/execute");
        assertThat(body(calls.get(1)).path("files").path(0).path("name").asText()).isEqualTo("main.js");
        assertThat(body(calls.get(2)).path("language").asText()).isEqualTo("c++");
        assertThat(body(calls.get(2)).path("files").path(0).path("name").asText()).isEqualTo("main.cpp");
        assertThat(PISTON.requests("/api/v2/runtimes")).as("runtimes are cached").hasSize(1);
        assertThat(runner.availableLanguages()).containsEntry("java", "15.0.2").containsEntry("cpp", "10.2.0").hasSize(4);
    }

    @Test
    void timeoutsIncludingOlderPistonSigkill() {
        PISTON.respond("POST", "/api/v2/execute", 200, Fixtures.pistonRun("", "", null, "SIGKILL", "TO"));
        assertThat(runner().runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT).tests().get(0).status())
                .isEqualTo(TestStatus.TIMEOUT);

        PISTON.respond("POST", "/api/v2/execute", 200, Fixtures.pistonRun("", "", null, "SIGKILL", null));
        assertThat(runner().runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT).tests().get(0).status())
                .isEqualTo(TestStatus.TIMEOUT);
    }

    @Test
    void runtimeErrorsAndOutputLimit() {
        PISTON.respond("POST", "/api/v2/execute", 200,
                Fixtures.pistonRun("", "Traceback: ZeroDivisionError", 1, null, "RE"));
        TestResult t = runner().runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT).tests().get(0);
        assertThat(t.status()).isEqualTo(TestStatus.RUNTIME_ERROR);
        assertThat(t.stderr()).contains("ZeroDivisionError");

        PISTON.respond("POST", "/api/v2/execute", 200, Fixtures.pistonRun("3\n", "", 1, null, null));
        assertThat(runner().runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT).tests().get(0).status())
                .as("non-zero exit fails even with the right output").isEqualTo(TestStatus.RUNTIME_ERROR);

        PISTON.respond("POST", "/api/v2/execute", 200, Fixtures.pistonRun("xxxx", "", null, "SIGKILL", "OL"));
        TestResult ol = runner().runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT).tests().get(0);
        assertThat(ol.status()).isEqualTo(TestStatus.RUNTIME_ERROR);
        assertThat(ol.stderr()).isEqualTo("Output limit exceeded.");
    }

    @Test
    void compileErrorStopsAfterTheFirstTest() {
        PISTON.respond("POST", "/api/v2/execute", 200, Fixtures.pistonCompileError("Main.java:1: error: ';' expected"));
        RunResult r = runner().runTests("java", "class Main {", tests("1 2", "3", "2 2", "4", "0 0", "0"), Limits.DEFAULT);

        assertThat(r.status()).isEqualTo(RunStatus.OK);
        assertThat(r.compileError()).contains("';' expected");
        assertThat(r.tests()).hasSize(3).extracting(TestResult::status).containsOnly(TestStatus.COMPILE_ERROR);
        assertThat(PISTON.requests("/api/v2/execute")).hasSize(1);
    }

    @Test
    void outputIsTruncatedTo2Kb() {
        PISTON.respond("POST", "/api/v2/execute", 200, Fixtures.pistonRun("x".repeat(10_000)));
        TestResult t = runner().runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT).tests().get(0);
        assertThat(t.status()).isEqualTo(TestStatus.FAILED);
        assertThat(t.actualOutput()).startsWith("xxx").endsWith("[truncated]").hasSizeLessThan(2100);
    }

    @Test
    void floatTolerancePerTest() {
        PISTON.respond("POST", "/api/v2/execute", 200, Fixtures.pistonRun("0.30000000000000004\n"));
        List<TestInput> t = List.of(new TestInput(0, "", "0.3", new BigDecimal("0.000001")), new TestInput(1, "", "0.3"));
        assertThat(runner().runTests("python", "print(0.1+0.2)", t, Limits.DEFAULT).tests())
                .extracting(TestResult::status).containsExactly(TestStatus.PASSED, TestStatus.FAILED);
    }

    @Test
    void busyWhenNoSlotFreesUpInTime() throws Exception {
        stubAdder(1_500);
        CodeRunnerServiceImpl runner = runner(1, 200);

        CompletableFuture<RunResult> first = CompletableFuture.supplyAsync(
                () -> runner.runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT));
        sleep(300);
        RunResult second = runner.runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT);

        assertThat(second.status()).isEqualTo(RunStatus.RUNNER_BUSY);
        assertThat(second.tests()).isEmpty();
        assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(RunStatus.OK);
    }

    @Test
    void batchWallClockTurnsUnfinishedTestsIntoTimeouts() {
        stubAdder(400);
        Limits tight = new Limits(3_000, 256L * 1024 * 1024, 10_000, 1_000);
        RunResult r = runner(1, 30_000).runTests("python", SUM,
                tests("1 1", "2", "1 2", "3", "1 3", "4", "1 4", "5", "1 5", "6"), tight);

        assertThat(r.status()).isEqualTo(RunStatus.OK);
        assertThat(r.tests()).hasSize(5);
        assertThat(r.tests().get(0).status()).isEqualTo(TestStatus.PASSED);
        assertThat(r.tests().get(4).status()).isEqualTo(TestStatus.TIMEOUT);
    }

    @Test
    void unsupportedUnconfiguredAndUnreachable() {
        stubAdder(0);
        assertThat(runner().runTests("cobol", "x", tests("1", "1"), Limits.DEFAULT).status())
                .isEqualTo(RunStatus.UNSUPPORTED_LANGUAGE);

        PISTON.respond("GET", "/api/v2/runtimes", 200, "[{\"language\":\"python\",\"version\":\"3.12.0\",\"aliases\":[]}]");
        assertThat(runner().runTests("java", "x", tests("1", "1"), Limits.DEFAULT).status())
                .as("not installed").isEqualTo(RunStatus.UNSUPPORTED_LANGUAGE);
        assertThat(PISTON.requests("/api/v2/execute")).isEmpty();

        PISTON.respond("GET", "/api/v2/runtimes", 500, "{}");
        assertThat(runner().runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT).status())
                .isEqualTo(RunStatus.RUNNER_UNAVAILABLE);

        PISTON.respond("GET", "/api/v2/runtimes", 200, Fixtures.pistonRuntimes());
        PISTON.respond("POST", "/api/v2/execute", 400, "{\"message\":\"run_timeout cannot exceed the configured limit of 3000\"}");
        assertThat(runner().runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT).status())
                .isEqualTo(RunStatus.RUNNER_UNAVAILABLE);

        when(settings.getPistonUrlOrNull()).thenReturn(null);
        RunResult none = runner().runTests("python", SUM, tests("1 2", "3"), Limits.DEFAULT);
        assertThat(none.status()).isEqualTo(RunStatus.RUNNER_UNAVAILABLE);
        assertThat(none.message()).contains("isn't configured");
    }

    @Test
    void selfTestRunsHelloWorldInEveryLanguage() {
        PISTON.respond("POST", "/api/v2/execute", 200, Fixtures.pistonRun("hello\n"));
        var checks = runner().selfTest();
        assertThat(checks).extracting(c -> c.language()).containsExactly("java", "python", "javascript", "cpp");
        assertThat(checks).allMatch(c -> c.ok());

        PISTON.respond("GET", "/api/v2/runtimes", 200, "[{\"language\":\"python\",\"version\":\"3.12.0\",\"aliases\":[]}]");
        var partial = runner().selfTest();
        assertThat(partial).filteredOn(c -> c.ok()).extracting(c -> c.language()).containsExactly("python");
        assertThat(partial.get(0).message()).contains("isn't available");
    }

    @Test
    void versionComparison() {
        assertThat(CodeRunnerServiceImpl.compareVersions("3.12.0", "3.10.0")).isPositive();
        assertThat(CodeRunnerServiceImpl.compareVersions("18.15.0", "1.32.3")).isPositive();
        assertThat(CodeRunnerServiceImpl.compareVersions("10.2", "10.2.0")).isZero();
    }
}
