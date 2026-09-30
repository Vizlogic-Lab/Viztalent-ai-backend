package com.smartstaff.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Runs untrusted code in the self-hosted Piston sandbox; never in this JVM.
 *  Languages use our names: java, python, javascript, cpp. */
public interface CodeRunnerService {

    List<String> LANGUAGES = List.of("java", "python", "javascript", "cpp");

    enum TestStatus { PASSED, FAILED, TIMEOUT, COMPILE_ERROR, RUNTIME_ERROR }

    /** OK means the batch ran (individual tests may still have failed). */
    enum RunStatus { OK, RUNNER_BUSY, RUNNER_UNAVAILABLE, UNSUPPORTED_LANGUAGE }

    record TestInput(int seq, String input, String expectedOutput, BigDecimal floatTolerance) {
        public TestInput(int seq, String input, String expectedOutput) {
            this(seq, input, expectedOutput, null);
        }
    }

    /** Per-test run limit, memory, compile limit, and wall clock for the whole batch. */
    record Limits(int runTimeoutMs, long memoryBytes, int compileTimeoutMs, int batchWallClockMs) {
        public static final Limits DEFAULT = new Limits(3_000, 256L * 1024 * 1024, 10_000, 15_000);
    }

    /** actualOutput and stderr are cut to 2 KB. timeMs and memoryBytes are null when Piston doesn't report them. */
    record TestResult(int seq, TestStatus status, String actualOutput, String stderr, Long timeMs, Long memoryBytes) {
        public boolean passed() {
            return status == TestStatus.PASSED;
        }
    }

    record RunResult(RunStatus status, String message, String language, String version,
                     String compileError, List<TestResult> tests) {
        public long passedCount() {
            return tests.stream().filter(TestResult::passed).count();
        }
    }

    /** Runs `code` once per test with the test's input on stdin. */
    RunResult runTests(String language, String code, List<TestInput> tests, Limits limits);

    /** Our language name -> installed Piston version (only languages available now). */
    Map<String, String> availableLanguages();

    record LanguageCheck(String language, boolean ok, String version, String message) {}

    /** Hello-world in every supported language. */
    List<LanguageCheck> selfTest();
}
