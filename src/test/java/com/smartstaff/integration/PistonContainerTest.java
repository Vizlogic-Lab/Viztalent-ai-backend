package com.smartstaff.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.service.CodeRunnerService.Limits;
import com.smartstaff.service.CodeRunnerService.RunResult;
import com.smartstaff.service.CodeRunnerService.RunStatus;
import com.smartstaff.service.CodeRunnerService.TestInput;
import com.smartstaff.service.CodeRunnerService.TestResult;
import com.smartstaff.service.CodeRunnerService.TestStatus;
import com.smartstaff.service.SettingsService;
import com.smartstaff.service.impl.CodeRunnerServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Runs against a real Piston container with Python downloaded from the
 *  internet, so it is excluded from the normal build. Run it with:
 *  mvn test -Dgroups=piston -DexcludedGroups= -Dtest=PistonContainerTest */
@Tag("piston")
class PistonContainerTest {

    private static final GenericContainer<?> PISTON = new GenericContainer<>("ghcr.io/engineer-man/piston")
            .withPrivilegedMode(true)
            // Piston expects its data dir to exist (docker-compose mounts a volume there).
            .withTmpFs(Map.of("/tmp", "rw,exec", "/piston/packages", "rw,exec"))
            .withEnv("PISTON_OUTPUT_MAX_SIZE", "65536")
            .withEnv("PISTON_RUN_MEMORY_LIMIT", "268435456")
            .withExposedPorts(2000)
            .waitingFor(Wait.forHttp("/api/v2/runtimes").forPort(2000).withStartupTimeout(Duration.ofMinutes(2)));

    private static CodeRunnerServiceImpl runner;

    @BeforeAll
    static void start() throws Exception {
        PISTON.start();
        String base = "http://" + PISTON.getHost() + ":" + PISTON.getMappedPort(2000) + "/api/v2";
        HttpResponse<String> install = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/packages"))
                        .timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"language\":\"python\",\"version\":\"3.x\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(install.statusCode()).as(install.body()).isLessThan(300);

        SettingsService settings = mock(SettingsService.class);
        when(settings.getPistonUrlOrNull()).thenReturn(base);
        runner = new CodeRunnerServiceImpl(settings, new ObjectMapper(), 8, 30_000, 0);
    }

    @AfterAll
    static void stop() {
        PISTON.stop();
    }

    private static RunResult run(String code, List<TestInput> tests) {
        return runner.runTests("python", code, tests, Limits.DEFAULT);
    }

    @Test
    void passesAndFails() {
        RunResult r = run("a, b = map(int, input().split())\nprint(a + b)",
                List.of(new TestInput(0, "1 2", "3"), new TestInput(1, "2 2", "5")));
        assertThat(r.status()).isEqualTo(RunStatus.OK);
        assertThat(r.tests()).extracting(TestResult::status).containsExactly(TestStatus.PASSED, TestStatus.FAILED);
    }

    @Test
    void infiniteLoopTimesOut() {
        RunResult r = run("while True:\n    pass", List.of(new TestInput(0, "", "")));
        assertThat(r.tests().get(0).status()).isEqualTo(TestStatus.TIMEOUT);
    }

    @Test
    void crashIsARuntimeError() {
        RunResult r = run("print(1 // 0)", List.of(new TestInput(0, "", "")));
        assertThat(r.tests().get(0).status()).isEqualTo(TestStatus.RUNTIME_ERROR);
        assertThat(r.tests().get(0).stderr()).contains("ZeroDivisionError");
    }

    @Test
    void noNetworkInsideTheSandbox() {
        String code = """
                import socket
                try:
                    socket.create_connection(("1.1.1.1", 80), timeout=2)
                    print("connected")
                except Exception:
                    print("blocked")
                """;
        RunResult r = run(code, List.of(new TestInput(0, "", "blocked")));
        assertThat(r.tests().get(0).status()).isEqualTo(TestStatus.PASSED);
    }
}
