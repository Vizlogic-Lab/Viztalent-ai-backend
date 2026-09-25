package com.smartstaff.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.AccountStatus;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.repository.UserRepository;
import com.smartstaff.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Base for tests that exercise the whole application — real Spring context,
 *  real Flyway migrations, real PostgreSQL (the schema uses jsonb,
 *  gen_random_uuid() and partial unique indexes, so H2 can't stand in) —
 *  with Gemini and Twilio replaced by a local {@link StubServer}.
 *
 *  One container and one stub server are shared by every subclass for the
 *  whole test run, and every subclass with the same configuration shares one
 *  cached Spring context, so the suite pays the startup cost once. Tests
 *  therefore must not assume an empty database: each creates its own
 *  accounts/jobs (unique names), and the two truly global tables —
 *  app_settings and the question bank — are cleared before every test.
 *
 *  Rate limiting is off by default (every MockMvc request comes from the
 *  same "client", so tests that log in a lot would otherwise trip it);
 *  RateLimitIntegrationTest turns it back on with low limits. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.rate-limit.enabled=false",
        "app.jwt.secret=test-jwt-secret-that-is-comfortably-longer-than-32-bytes",
        "app.encryption.key=test-encryption-key-0123456789abcdef",
})
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final StubServer STUB = StubServer.start();
    private static final Path STORAGE_DIR;

    static {
        POSTGRES.start();
        try {
            STORAGE_DIR = Files.createTempDirectory("viztalent-test-storage");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.gemini.base-url", STUB::baseUrl);
        registry.add("app.twilio.api-base-url", STUB::baseUrl);
        registry.add("app.storage.dir", STORAGE_DIR::toString);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected UserRepository users;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JwtService jwtService;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void resetSharedState() {
        STUB.reset();
        jdbc.update("delete from app_settings");
        jdbc.update("delete from question_bank_uploads"); // cascades to question_bank_items
    }

    // ── accounts ────────────────────────────────────────────────────────

    /** A user plus a valid bearer token for them. */
    protected record Account(User user, String token) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    protected Account newAdmin() {
        String suffix = shortId();
        return account(new User(Role.ADMIN, "Admin " + suffix, "admin-" + suffix + "@test.local", null,
                passwordEncoder.encode("Passw0rd!"), AccountStatus.APPROVED));
    }

    protected Account newEmployee() {
        return newEmployee(AccountStatus.APPROVED);
    }

    protected Account newEmployee(AccountStatus status) {
        String suffix = shortId();
        return account(new User(Role.USER, "Employee " + suffix, "emp-" + suffix + "@test.local", "T" + suffix.toUpperCase(),
                passwordEncoder.encode("Passw0rd!"), status));
    }

    private Account account(User user) {
        User saved = users.save(user);
        return new Account(saved, jwtService.issueToken(saved.getId(), saved.getRole().name()));
    }

    // ── requests ────────────────────────────────────────────────────────

    protected ResultActions getAs(Account who, String url) throws Exception {
        return mvc.perform(withAuth(get(url), who));
    }

    protected ResultActions postJsonAs(Account who, String url, Object body) throws Exception {
        return mvc.perform(withAuth(post(url), who)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request, Account who) {
        return who == null ? request : request.header("Authorization", who.bearer());
    }

    protected JsonNode bodyOf(ResultActions result) throws Exception {
        // JSON is UTF-8; MockHttpServletResponse would otherwise guess ISO-8859-1 and garble e.g. the '…' in masked keys.
        return json.readTree(result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ── common setup ────────────────────────────────────────────────────

    /** Creates a job through the real API (skills-only JD) and returns its id. */
    protected String createJob(Account owner, String title, String skillsCsv) throws Exception {
        ResultActions result = postJsonAs(owner, "/api/upload_jd_skills", java.util.Map.of("skills", skillsCsv, "title", title));
        return bodyOf(result).path("job_id").asText();
    }

    protected void setGeminiKey(Account admin, String key) throws Exception {
        postJsonAs(admin, "/api/config/gemini", java.util.Map.of("api_key", key));
    }

    protected void setPublicBaseUrl(Account admin, String url) throws Exception {
        postJsonAs(admin, "/api/config/public_url", java.util.Map.of("public_base_url", url));
    }

    protected void setTwilio(Account admin, String sid, String authToken, String fromNumber) throws Exception {
        postJsonAs(admin, "/api/config/twilio", java.util.Map.of(
                "account_sid", sid, "auth_token", authToken, "from_number", fromNumber, "persist", true));
    }

    /** Uploads resumes (plain-text files) to a job through the real multipart endpoint.
     *  `namesAndTexts` alternates filename, file content. */
    protected ResultActions uploadResumes(Account who, String jobId, String... namesAndTexts) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/upload_resumes");
        request.param("session_id", jobId);
        for (int i = 0; i < namesAndTexts.length; i += 2) {
            request.file(new org.springframework.mock.web.MockMultipartFile(
                    "files", namesAndTexts[i], "text/plain", namesAndTexts[i + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return mvc.perform(withAuth(request, who));
    }

    /** Uploads one file as multipart under the given form-field name. */
    protected ResultActions uploadFile(Account who, String url, String field, String filename, String text) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(url)
                .file(new org.springframework.mock.web.MockMultipartFile(
                        field, filename, "text/plain", text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return mvc.perform(withAuth(request, who));
    }

    /** Runs `count` copies of a task at the same instant (released together) and collects HTTP statuses. */
    protected java.util.List<Integer> inParallel(int count, java.util.concurrent.Callable<Integer> task) throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(count);
        var startGate = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return task.call();
            }));
        }
        startGate.countDown();
        var results = new java.util.ArrayList<Integer>();
        for (var f : futures) results.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();
        return results;
    }

    protected static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
