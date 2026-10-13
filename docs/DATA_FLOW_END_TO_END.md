npm run dev
# End-to-End Data Flow (Backend Developer Guide)

> Audience: backend developers. This explains how a request travels from the
> browser, through the Spring Boot app (`backend-classic`), into PostgreSQL and
> the local file store, and back — including where data is held at every stage,
> how it is persisted, and how slow work (AI, screening, phone calls) is handled
> off the request thread.
>
> This is the classic layered Spring Boot app (`controller → service →
> repository → entity`). See also [`ARCHITECTURE_DIAGRAMS.md`](ARCHITECTURE_DIAGRAMS.md),
> [`API_REFERENCE.md`](API_REFERENCE.md), and [`FOLDER_STRUCTURE.md`](FOLDER_STRUCTURE.md).

---

## 1. The big picture (where data lives)

```
┌────────────┐   /api/... (same origin)   ┌─────────────────────────────┐
│  Browser    │ ─────────────────────────▶ │  Vite dev server (frontend)  │
│ React 19 +  │ ◀───────────────────────── │  proxies /api → BACKEND_URL  │
│   Vite      │        JSON / files         └──────────────┬──────────────┘
└────────────┘                                             │ http (localhost)
                                                            ▼
                                        ┌───────────────────────────────────┐
                                        │     backend-classic (Spring Boot)   │
                                        │     port 8000 (8010 on this box)    │
                                        │                                     │
                                        │  Filters → Controller → Service →   │
                                        │  Repository (JPA) → Entities        │
                                        └───┬─────────────┬─────────────┬─────┘
                                            │             │             │
                           ┌────────────────▼──┐  ┌───────▼──────┐  ┌───▼─────────────┐
                           │ PostgreSQL 16      │  │ Local disk    │  │ External APIs    │
                           │ :15433             │  │ ./storage     │  │ Gemini / Twilio  │
                           │ smartstaff_classic │  │ (JD + resume  │  │ / Piston (code   │
                           │ (all app state)    │  │  files)       │  │  runner)         │
                           └────────────────────┘  └──────────────┘  └──────────────────┘
```

**Four places data is stored:**

| Store | What lives there | Managed by |
|---|---|---|
| **PostgreSQL** (`smartstaff_classic` on `:15433`) | All structured state: users, jobs, skills, candidates, scores, assessments, submissions, interviews, invites, activity, settings. Schema is owned by **Flyway** (`V1__…` → `V29__…`). | Spring Data JPA (Hibernate) |
| **Local disk** (`./storage`, from `app.storage.dir`) | Uploaded binary files: JD documents and resumes. The DB stores only the **path + SHA-256 hash**, not the bytes. | `FileStorageService` |
| **External APIs** | Nothing persisted by them; responses flow back in-process. Gemini (skill/question/narrative generation), Twilio (phone interviews), Piston (candidate code execution). | `GeminiClient`, `TwilioClient`, `CodeRunnerService` |
| **Browser `localStorage`** | JWT token, and some candidate UI state. Server treats the token as the credential on every request. | Frontend |

`ddl-auto: validate` means **Hibernate never changes the schema** — if an entity
and the Flyway-built table disagree, the app refuses to start. Schema changes are
a new migration file under [`src/main/resources/db/migration`](../src/main/resources/db/migration),
nothing else.

---

## 2. The request pipeline (every call goes through this)

A request to `/api/...` passes through an ordered chain before any business code
runs. Defined in [`SecurityConfig.filterChain`](../src/main/java/com/smartstaff/config/SecurityConfig.java):

```
Request
  │
  ▼
CorsFilter ............ allows the Vite origins (app.cors.allowed-origins)
  │
  ▼
RequestIdFilter ....... stamps an X-Request-Id into the MDC (shows in every log line)
  │
  ▼
RateLimitFilter ....... per-IP, per-minute caps on unauthenticated endpoints
  │                      (login, token endpoints, candidate "run code")
  ▼
DownloadSignatureFilter  authenticates signed download URLs as the signing user
  │
  ▼
JwtAuthFilter ......... reads "Authorization: Bearer <jwt>", resolves it to a User,
  │                      sets the SecurityContext, bumps users.last_seen_at.
  │                      Blocks everything but /me, /change_password, /logout
  │                      if the account still has must_change_password.
  ▼
authorizeHttpRequests  URL-level role gate (ADMIN / USER / REVIEWER; public list)
  │
  ▼
Controller (@PreAuthorize is a second lock) → Service → Repository
```

Key facts a backend dev needs:

- **Stateless.** `SessionCreationPolicy.STATELESS`, CSRF disabled. The JWT is the
  only credential; there is no server-side session. Revoking an employee
  (un-approving them) kills their existing token on the **next** request, because
  [`JwtAuthFilter`](../src/main/java/com/smartstaff/security/JwtAuthFilter.java)
  re-checks account status every time (see `isActive`).
- **Authorization is enforced twice.** First at the URL in `SecurityConfig`
  (so a non-admin is rejected with 403 *before* the body is parsed), then again by
  `@PreAuthorize` / `@jobAccess.*` guards on the controller methods.
- **Public endpoints** (no bearer token): `/api/auth/login`, `/api/auth/signup`,
  the candidate token endpoints (`/api/interview/by_token/**`,
  `/api/candidate/assessment/**`, `/api/assessment/*_by_token/**`), Twilio's TwiML
  webhooks (gated by `X-Twilio-Signature` instead), and `/actuator/health`.
- **Errors** come back as a uniform JSON shape (`ApiErrorResponse`), including auth
  (401) and access-denied (403), so the frontend can handle them consistently.

---

## 3. Persistence mechanics

- **Entities** ([`entity/`](../src/main/java/com/smartstaff/entity)) are JPA
  `@Entity` classes mapped 1:1 to Flyway tables. JSON-ish columns (score
  breakdowns, matched/missing skills, proctoring events, answers) are stored as
  `jsonb`.
- **Repositories** ([`repository/`](../src/main/java/com/smartstaff/repository))
  are Spring Data interfaces; derived query names (e.g.
  `findByOwnerIdIgnoreCaseAndContentHash`, `findByJobIdOrderByUploadedAtDesc`)
  generate the SQL.
- **Transactions**: services are `@Transactional`. `open-in-view: false` — lazy
  associations must be loaded inside the service method, not in the controller/
  serialization layer.
- **DTO boundary**: controllers never return entities.
  [`mapper/`](../src/main/java/com/smartstaff/mapper) classes convert entities →
  response DTOs, so column names and wire field names evolve independently.
- **Files**: `FileStorageService` writes bytes under `./storage/...` and returns a
  relative path; the entity stores that path plus a SHA-256 hash of the content.
  The hash is how duplicate JDs are detected per owner.

---

## 4. Worked example — JD upload → screening (the core flow)

This is the flow most of the product hangs off. Follow the data.

### 4.1 Upload a job description — `POST /api/upload_jd`

[`JobController.uploadJd`](../src/main/java/com/smartstaff/controller/JobController.java)
→ `JobServiceImpl`:

1. Read the `MultipartFile` bytes (capped at 25 MB per file / 100 MB per request,
   `spring.servlet.multipart`).
2. `contentHash = sha256Hex(bytes)` → look up `findByOwnerIdIgnoreCaseAndContentHash`.
   If this owner already uploaded identical bytes, short-circuit as a duplicate.
3. `extractTextBestEffort(bytes, filename)` — `TextExtractor` pulls plain text out
   of PDF/Word.
4. `ExperienceParser` estimates an experience range from the text; skill
   extraction fills `job_skills`.
5. `storeFile(file, "jobs/<uuid>")` writes the original file to **disk**; the
   returned path goes into `job.filePath`. The bytes never go into Postgres.
6. `jobRepository.save(job)` persists the row (id, display_no `JD-0001`, title,
   `file_path`, `jd_text`, `content_hash`, `uploaded_by`, …).
7. `events.publishEvent(new JdUploadedEvent(job.getId()))` — a Spring application
   event (see §5).
8. Returns a `JdUploadResponse` DTO with the new `job_id` and a human reply string.

**Data at rest after this call:** one `jobs` row + N `job_skills` rows in Postgres;
one file under `./storage/jobs/<uuid>`; one `activity_events` row (via the event
listener).

### 4.2 Attach resumes — `POST /api/upload_resumes`

Per file: store bytes to `./storage/jobs/<jobId>/resumes`, extract text, save a
`resumes` row (`job_id`, `filename`, `file_path`, `extracted_text`). Filenames are
de-duplicated against existing resumes for the job.

### 4.3 Run screening — `POST /api/run_screening` (asynchronous)

`ScreeningServiceImpl`:

1. Load the job's required / nice-to-have skills and all its resumes.
2. For each resume, `scoreCandidate(...)` computes a **deterministic** fit score
   in code (`ScoringRules` / `SkillDictionary` — normalize + fuzzy-match skills,
   weight experience). The LLM may help *extract* skills from messy text, but the
   **score itself is never produced by the LLM**.
3. `candidateRepository.saveAll(scored)` — one `candidates` row per resume, with
   `fit_score`, `score_breakdown` (jsonb), `matched_skills`, `missing_skills`,
   `years_experience`.
4. Candidates scoring **≥ 50** are the shortlist (the `INTERVIEW_THRESHOLD`).
5. Progress is exposed via `GET /api/progress` while the run executes, and an
   event feeds the activity log.

**Why async:** screening touches disk, text extraction, and possibly AI for many
resumes; it runs on the async executor (§6) so the HTTP call returns immediately
and the UI polls `/api/progress`.

### 4.4 Read it back — Candidates table

`GET /api/jobs/{id}` / the candidates endpoints return mapper-built DTOs sorted by
`fit_score` descending (matching the frontend's own table sort). No file bytes are
sent — downloads go through the signed-URL path (§7).

---

## 5. Cross-feature communication — application events

Features don't call each other's services for side effects; they **publish events**
and the `activity` + email features listen. This keeps the activity feed and
notifications decoupled from the features that cause them.

```
JobServiceImpl        ──JdUploadedEvent──▶        ActivityService  ──▶ activity_events row
ScreeningService      ──(screening done)──▶       ActivityService
AssessmentService     ──AssessmentQueuedEvent──▶  async generation worker
Attempt submission    ──AttemptSubmittedEvent──▶  evaluation engine
InterviewService      ──InterviewCompletedEvent──▶ ActivityService
Any feature           ──EmailRequestedEvent──▶    EmailService (sends via SMTP, logs to email_log)
```

The Dashboard, Analytics, and the notification bell (polled ~every 10s) all read
from `activity_events`, which is **built entirely from these events** — never by
the features writing to each other's tables.

---

## 6. Asynchronous & scheduled work

Slow work is pushed off the request thread so HTTP stays snappy. Config in
`AsyncExecutorConfig` and `SchedulingConfig`; progress lives in the DB/memory and
is polled by the frontend.

| Task | Kicked off by | Progress read via |
|---|---|---|
| Resume screening | `POST /api/run_screening` | `GET /api/progress` |
| Assessment generation (AI) | `POST /api/assessment/generate` | `GET /api/assessment/status/{jobId}` |
| Answer evaluation / grading | `AttemptSubmittedEvent` + periodic sweep | submission status |
| Twilio phone interview | `POST /api/interview/place_call` | `GET /api/interview/call_status/{id}` + webhooks |

Scheduled sweeps (e.g. evaluation retry, auto-submit of running assessments) run on
fixed intervals from `application.yml` (`app.evaluation.*`, `app.assessment.*`).
This is **single-node** (in-memory rate limits and progress) — scaling out would
need Redis-backed shared state.

---

## 7. File downloads — two ways in

Binary files are never streamed through the normal JSON endpoints. A download is
authorized either by:

1. **Bearer token** — the GET download routes require `ADMIN`/`USER`
   (`/api/jd/*/download`, `/api/resumes/**`, `/api/download_report`,
   `/api/scorecard/**`), or
2. **Signed URL** — `POST /api/downloads/sign` issues a short-lived signed link;
   `DownloadSignatureFilter` validates the signature and authenticates the request
   as the user who signed it. This lets the browser open a file in a new tab (where
   it can't send the `Authorization` header) without exposing the file publicly.

The handler reads bytes from `./storage/<path>` using the `file_path` stored on the
entity and streams them with the right content type.

---

## 8. External integrations (data leaving the app)

| Integration | Direction | Data flow | Secret storage |
|---|---|---|---|
| **Gemini** (`GeminiClient`) | out | Skill extraction, assessment/question generation, result narration, recruiter chat (`/api/universal_execute`). Daily call cap (`app.ai.daily-call-cap`) pauses non-critical AI. | API key saved in **Settings**, encrypted at rest via `CryptoService` (`SETTINGS_ENCRYPTION_KEY`), masked in `/api/config`. Env `GEMINI_API_KEY` can override. |
| **Twilio** (`TwilioClient`) | out + in | Outbound calls; inbound TwiML webhooks hit `/api/interview/twiml/**` (public, gated by `X-Twilio-Signature` via `TwilioSignatureValidator`). | SID/token encrypted in Settings. |
| **Piston** (`CodeRunnerService`) | out | Runs candidate code for coding questions in a sandbox — never on the host. Concurrency/queue caps in `app.runner.*`. | Piston URL in Settings. |
| **SMTP** (`EmailService`) | out | Invite / notification emails, triggered by `EmailRequestedEvent`; each send is logged to `email_log`. | SMTP password encrypted in Settings. |

Secrets are **encrypted in the database** and **masked in API responses** — they
are never returned in clear text.

---

## 9. Candidate-facing flows (token is the credential)

Candidates are not logged-in users. They reach the app through **single-use,
expiring invite tokens**:

- `InviteService` mints an invite, stores a **hash** of the token (`invites` table,
  `token_hash`, `expires_at`, `used_at`), and builds the link via `InviteLinks`.
- Candidate endpoints (`/api/candidate/assessment/**`, `/api/interview/by_token/**`,
  `/api/assessment/*_by_token/**`) are public in `SecurityConfig` because the token
  itself authorizes the request.
- Consuming a token is an atomic update guarded on `used_at IS NULL AND expires_at
  > now()`, so a link can't be replayed.
- During an assessment, proctoring events and per-question answers are written as
  the candidate works; submission raises `AttemptSubmittedEvent`, which drives
  evaluation/grading and the scorecard PDF (`ScorecardPdfService`).

> Note (local dev): minted links hardcode public base **:5173**; rewrite the
> host/port to open them against the running frontend port locally.

---

## 10. Configuration & environment (what wires it together)

From [`application.yml`](../src/main/resources/application.yml) /
[`application-dev.yml`](../src/main/resources/application-dev.yml):

| Setting | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8000` (use **8010** on this Windows box — 8000 is OS-reserved) | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:15433/smartstaff_classic` | Postgres (runs in Docker on :15433) |
| `APP_JWT_SECRET` | — (required) | JWT signing; dev profile loads it from `backend-classic/.env` |
| `JWT_TTL_MINUTES` | `1440` | Token lifetime |
| `CORS_ORIGINS` | `http://localhost:5173,http://localhost:4173` | Allowed browser origins |
| `STORAGE_DIR` | `./storage` | Uploaded file root |
| `SETTINGS_ENCRYPTION_KEY` | — (required) | Encrypts secrets saved in Settings |
| `app.jobs.max-retained` | `100` | Newest 100 JDs retained |

Run profile is `dev` (`SPRING_PROFILES_ACTIVE=dev`), which reads the git-ignored
`.env`; without it the app crashes on the missing `APP_JWT_SECRET` placeholder.
Postgres must be up (Docker, :15433) before the app starts — Flyway runs the
migrations on boot, then `ddl-auto: validate` checks the entities match.

---

## 11. One-request cheat sheet

> *"Admin uploads a JD and screens resumes"* — the whole trip:

1. Browser `POST /api/upload_jd` (multipart) with `Authorization: Bearer <jwt>`.
2. Vite proxies `/api` → backend `:8000/:8010`.
3. CORS → RateLimit → JwtAuthFilter resolves the admin User, sets SecurityContext,
   bumps `last_seen_at`.
4. URL gate: default rule requires `ADMIN`/`USER` → allowed.
5. `JobController.uploadJd` → `JobServiceImpl`: hash, de-dupe, extract text, store
   file to `./storage`, `INSERT` into `jobs` + `job_skills`, publish
   `JdUploadedEvent`.
6. Listener writes an `activity_events` row.
7. DTO (`JdUploadResponse`) serialized back → through the proxy → browser.
8. Browser `POST /api/upload_resumes`, then `POST /api/run_screening` (async) and
   polls `GET /api/progress`.
9. `ScreeningServiceImpl` scores each resume **deterministically**, `saveAll`
   `candidates`, shortlist = score ≥ 50.
10. Browser reads the candidates table (mapper DTOs, sorted by score desc).

Every structured write landed in Postgres; every file landed on disk as a path +
hash; every notable step left an event in the activity feed.
