# backend-classic — Folder Structure

Standard layered Spring Boot architecture (package-by-layer, not package-by-feature):
`controller → service (interface) → service.impl → repository → entity`, with
`dto` / `mapper` / `exception` / `security` / `filter` / `config` / `client` / `util` as supporting layers.

```
smartstaff/backend-classic/
├── docker-compose.yml          Isolated local Postgres for this backend only (port 15433)
├── pom.xml                     Maven build — Spring Boot 3.3.5, Java 21
├── docs/                       This folder (FEATURES.md details, FEATURE_STATUS.md complete-or-not checklist)
├── src/test/                   Automated tests — see "Test layout" below
└── src/main/
    ├── resources/
    │   ├── application.yml     Server port, datasource, JWT, CORS, storage, multipart limits,
    │   │                       external-API base URLs, rate limits, actuator/log settings
    │   │                       (every value has an environment-variable override)
    │   ├── skills.txt           Curated skill dictionary (one skill per line) for JD/resume matching
    │   └── db/migration/        Flyway migrations (V1, V2, ... — never edit an applied one, add a new file)
    │       ├── V1__auth.sql     users table
    │       ├── V2__jobs.sql     jobs, resumes tables
    │       ├── V3 … V8          candidates, app_settings, question bank, assessments + invites,
    │       │                    interviews, interview call status (see the migrations folder)
    │       └── (one new file per change — never edit an applied one)
    └── java/com/smartstaff/
        ├── BackendClassicApplication.java   Spring Boot entry point
        │
        ├── controller/          REST controllers — HTTP in/out only, no business logic.
        │                        Maps directly onto the frontend's expected /api/... paths.
        │
        ├── service/             One interface per business capability (e.g. AuthService).
        │   └── impl/            One implementation class per interface (e.g. AuthServiceImpl).
        │                        Controllers depend on the interface, never the impl directly.
        │
        ├── repository/          Spring Data JPA repositories (one per entity/aggregate root).
        │
        ├── entity/              JPA @Entity classes — the persistence model. Never returned
        │                        directly from a controller; always mapped to a DTO first.
        │
        ├── dto/
        │   ├── request/         Inbound request bodies (validated with jakarta.validation).
        │   └── response/        Outbound response bodies. Shaped to match exactly what
        │                        smartstaff/frontend already expects (field names, nullability).
        │
        ├── mapper/               entity <-> dto conversion (e.g. UserMapper). Keeps that
        │                        logic out of both the entity and the service.
        │
        ├── exception/            ApiException (thrown anywhere to produce a clean error
        │                        response) + GlobalExceptionHandler (@RestControllerAdvice
        │                        that turns exceptions into the frontend's {ok:false,
        │                        message, code} error shape).
        │
        ├── security/             JwtService (issue/validate tokens) + JwtAuthFilter
        │                        (reads the Authorization header, resolves it to a User,
        │                        rejects un-approved employees, bumps last_seen_at) +
        │                        JobAccessGuard (per-job ownership, used from @PreAuthorize).
        │
        ├── filter/               Servlet filters: RequestIdFilter (request id in logs +
        │                        response header, access log, invite tokens masked) and
        │                        RateLimitFilter (per-client limits on login/signup and the
        │                        interview-link endpoints; RateLimiter is its counter).
        │
        ├── config/               Spring @Configuration classes: SecurityConfig (filter
        │                        chain, CORS, admin-only route rules, password encoder),
        │                        DemoDataSeeder (inserts the two demo accounts on first
        │                        boot), ProductionSecretsGuard (refuses to start under the
        │                        `prod` profile with development-default secrets).
        │
        ├── client/               GeminiClient and TwilioClient — the ONLY classes that call
        │                        external APIs (timeouts set, base URLs configurable so tests
        │                        can substitute a stub). Services depend on these, never on
        │                        a raw HTTP client.
        │
        └── util/                 Stateless helpers with no business rules of their own:
                                  FileStorageService (local-disk upload storage + SHA-256
                                  hashing), SkillDictionary (loads skills.txt, word-boundary
                                  matching), TextExtractor (Apache Tika wrapper for PDF/
                                  DOCX/TXT text extraction), ExperienceParser,
                                  QuestionBankFileParser (CSV/JSON/XLSX), CryptoService
                                  (AES-256-GCM for secrets at rest), TwilioSignatureValidator
                                  (HMAC-SHA1 check of Twilio webhooks).
```

## Test layout

```
src/test/java/com/smartstaff/
├── support/        IntegrationTestBase (shared PostgreSQL container + Spring context, account/
│                   request helpers), StubServer (in-JVM stand-in for Gemini and Twilio),
│                   Fixtures (canned Gemini/Twilio payloads + an independent Twilio signer)
├── integration/    End-to-end tests: real Spring app + real PostgreSQL + MockMvc, one class
│                   per feature area (auth, ownership, screening, assessments, interviews,
│                   phone interviews, chat, settings, rate limiting, actuator)
├── util/           Unit tests for the stateless helpers
├── filter/         Unit tests for RateLimiter and RequestIdFilter
├── security/       Unit tests for JwtService and JobAccessGuard
└── config/         Unit test for ProductionSecretsGuard
```

`mvn test` runs all 242 (Docker must be running — see "Automated tests" in FEATURES.md).

## Layer rules (why it's organized this way)

1. **Controllers stay thin.** They validate input (via `@Valid` DTOs), call one service
   method, and return a DTO. No queries, no business rules, no entity references.
2. **Services own business logic** and are always programmed against an interface
   (`AuthService`), never the concrete class — this is what makes the layer mockable in
   tests and swappable without touching controllers.
3. **Entities never leave the service layer.** A controller/DTO never sees a JPA entity;
   a `mapper` class converts entity → response DTO. This keeps persistence details
   (column names, JPA annotations, lazy-loading) from leaking into the API contract.
4. **DTOs are the API contract**, and that contract is fixed by the frontend — see
   `smartstaff/frontend/FEATURES.md` (§14, the endpoint list) and the frontend source
   itself for the exact field names each response must have.
5. **External calls live in `client/` only.** A service that needs Gemini or Twilio depends on
   `GeminiClient` / `TwilioClient`; nothing else builds URLs or HTTP clients. That is what lets the
   test suite swap the real services for a stub by changing one configuration value.
6. **Exceptions are centralized.** Business code throws `ApiException(status, message,
   code)`; `GlobalExceptionHandler` is the only place that turns exceptions into HTTP
   responses, so every endpoint returns errors in the one shape the frontend already
   knows how to render.

## Adding a new feature module

Every feature adds one file per existing folder, following the same pattern: an
`entity/`, a `repository/`, request/response `dto/`s, a `mapper/`, a `service/` interface
+ `service/impl/` implementation, and a `controller/` — plus a new Flyway migration if it
needs new tables. **Jobs (Phase 2) followed exactly this shape**: `entity/Job.java` +
`entity/Resume.java`, `repository/JobRepository.java` + `ResumeRepository.java`,
`dto/request/JdSkillsOnlyRequest.java`, seven `dto/response/*.java` files, `mapper/
JobMapper.java`, `service/JobService.java` + `service/impl/JobServiceImpl.java`,
`controller/JobController.java`, and `V2__jobs.sql`. It also added three reusable helpers
under `util/` (`FileStorageService`, `SkillDictionary`, `TextExtractor`,
`ExperienceParser`) that later phases (screening, assessment) are expected to reuse
rather than duplicate.

**Recruiter AI chat (`universal_execute`, closing out Phase 5)** needed no new entity,
repository, or migration — it's pure orchestration. It added
`service/RecruiterChatService.java` + `service/impl/RecruiterChatServiceImpl.java`
(depends on the existing `ScreeningService` and `SettingsService` rather than duplicating
resume-scoring or key-lookup logic) and three request DTOs
(`UniversalExecuteRequest`, `PlatformConfigRequest`, `ToolDeclarationRequest`), but zero
new response DTOs — its response is exactly `dto/response/RunScreeningResponse.java`
(Phase 3), reused as-is. The one new endpoint was added to the existing
`controller/ScreeningController.java` rather than a new controller, since the design doc
groups `universal_execute` under the same `screening-service` module as `run_screening`.

**Interviews (Phase 7)** followed the full shape again, plus reused two pieces from
Phase 6 rather than duplicating them: `entity/Interview.java` + `InterviewTurn.java`
(and `Invite.java` gained one new nullable field, `interview`), `repository/
InterviewRepository.java` + `InterviewTurnRepository.java` (plus one new method,
`InviteRepository.consume`, the atomic single-use redemption V6's invites table was
always meant to support but nothing had exercised yet), five request/response DTO
pairs, `mapper/InterviewMapper.java`, `service/InterviewService.java` +
`service/impl/InterviewServiceImpl.java` (depends on `SettingsService` for the Gemini
key and `CandidateRepository` to ground questions in a real résumé), a new
`controller/InterviewController.java` (its own controller this time, not folded into
an existing one, since the design doc gives `interview` its own top-level module
distinct from `assessment`/`screening`), and `V7__interviews.sql`. `V7` also alters a
V6 table (`ALTER TABLE invites ADD COLUMN interview_id ...`) rather than only adding
new tables — still a *new* migration file, never touching `V6__assessments.sql` itself.

**Assessments + invites (Phase 6)** is a case of one phase splitting into *two* sibling
services instead of one, because "generate/grade an assessment" and "mint a candidate
link" are different enough responsibilities to keep separate: `entity/Assessment.java` +
`AssessmentQuestion.java` + `Invite.java`, `repository/AssessmentRepository.java` +
`AssessmentQuestionRepository.java` + `InviteRepository.java`, `dto/request/
AssessmentGenerateRequest.java` + `InviteMintRequest.java`, five `dto/response/*.java`
files, `service/AssessmentService.java` + `service/impl/AssessmentServiceImpl.java`
*and* `service/InviteService.java` + `service/impl/InviteServiceImpl.java`,
`controller/AssessmentController.java` (extended) + `controller/InviteController.java`
(new), and `V6__assessments.sql`. `InviteServiceImpl` reuses
`FileStorageService.sha256Hex` (Phase 2) rather than adding a second hashing helper, and
`AssessmentServiceImpl` reuses `QuestionBankItemRepository` (Phase 5) to draw from the
existing bank rather than duplicating question storage.

## Migrations added since this doc's tree diagram was first written

The ASCII tree above only shows `V1`/`V2` for brevity; every phase since has added one
more file to `src/main/resources/db/migration/`, never editing an already-applied one:
`V3__screening.sql` (candidates), `V4__settings.sql` (app_settings), `V5__question_bank.sql`
(question_bank_uploads, question_bank_items), `V6__assessments.sql` (assessments,
assessment_questions, invites), `V7__interviews.sql` (interviews, interview_turns, plus
an `invites.interview_id` column added onto V6's table). See `docs/FEATURES.md`'s "Data
model so far" section for each migration's exact columns.
