# SmartStaff (Viztalent AI) — New Joiner Onboarding

A guided walkthrough of the whole system for someone joining the team. Read this
first, then the docs it points to.

Related: [ROLES_AND_WORKFLOW.md](ROLES_AND_WORKFLOW.md) (the story) ·
[FOLDER_STRUCTURE.md](FOLDER_STRUCTURE.md) (the layers) ·
[../../../../Users/vizlo/OneDrive/Desktop/viztalent_ai/SMARTSTAFF_BACKEND_DESIGN.md](../SMARTSTAFF_BACKEND_DESIGN.md)
(the architecture decisions) · [FEATURES.md](FEATURES.md) (full technical detail) ·
[FEATURE_STATUS.md](FEATURE_STATUS.md) (what is / isn't built) ·
[INTERVIEW_CHANNELS_DESIGN.md](INTERVIEW_CHANNELS_DESIGN.md) (video + phone design) ·
[ARCHITECTURE_DIAGRAMS.md](ARCHITECTURE_DIAGRAMS.md) (Mermaid architecture + ER diagrams).

> Teach this top-down: start with **what the product does**, then peel down to the
> code. Don't open a `.java` file until Layers 1–3 make sense.

---

## Layer 1 — What the product is

SmartStaff automates hiring:

> A recruiter uploads a **job description** and a pile of **resumes**; the system
> **scores and ranks** the candidates; the good ones get an **AI interview** (in the
> browser or over the phone via Twilio); the transcript comes back to the recruiter
> to make a decision.

It's a **backend** (`backend-classic`, this repo — Spring Boot 3.3.5 / Java 21 /
PostgreSQL) that a **React 19 + Vite frontend** talks to on `http://localhost:8000`.

### The actors (the most important slide)

| Role | Access | Gets in via |
|---|---|---|
| **Admin** | Everything: all jobs, settings, employees, question bank | Email + password |
| **Employee** | Same daily work, but **only their own jobs** | Employee ID + password, **an admin must approve first** |
| **Candidate** | Takes one interview, **no account** | A single-use, expiring link |
| **Visitor** | Login / signup pages only | Nothing |
| **Twilio** | The phone system (not a person) | Signed webhooks only |

Full role matrix: [ROLES_AND_WORKFLOW.md](ROLES_AND_WORKFLOW.md) §1.

---

## Layer 2 — The end-to-end flow (the happy path)

This is the spine of the whole system. Memorize this sequence.

```
ADMIN setup:  login -> Settings (Gemini key, public URL, link TTL, Twilio) -> approve employees
     |
HR (admin/employee):
  1. Upload JD ---------> extract skills + years exp, assign JD-0001, dedupe
  2. Upload resumes ----> store files, extract text (Apache Tika)
  3. Run screening -----> DETERMINISTIC score /100 (no AI), rank candidates
  4. Candidates page ---> score >= 50 -> Invite/Interview,  < 50 -> Rejection
  5. Mint single-use link -> Gemini writes 5 questions from JD + resume
  6. "Open in mail client" (backend never sends email -- just prepares a mailto: link)
     |
CANDIDATE (no login):
  7a. Browser interview -> mic -> speech-to-text answers -> submit once
  7b. Phone interview ---> Twilio calls -> AI voice asks -> answers saved
     |
HR again:
  8. Transcript appears on Candidates page -> read -> decide
```

Two things that surprise people:

- **Scoring is deterministic** — computed in code (required skills 85 pts,
  nice-to-have 15, experience +/-5). The same resume always gets the same score. The
  LLM is only used to *extract* skills from messy text, never to score.
- **The backend sends no email.** It builds a `mailto:` link; the recruiter presses
  Send in their own mail app. So the system can't tell if a mail was sent/opened.

Business rules worth knowing: interview threshold = fit score **>= 50**; assessment
pass = **50%**; JD retention = newest **100** (FIFO); invite links single-use,
default TTL **48h**. See SMARTSTAFF_BACKEND_DESIGN.md §1.

---

## Layer 3 — The architecture (why it's shaped this way)

**Modular monolith:** one deployable Spring Boot app, split into strict modules.
Almost all data is scoped to a single job, so a monolith keeps real joins and single
transactions (e.g. deleting a JD cascades to all its data). It's built so any module
can later be extracted into a microservice — modules own their data and (by design)
talk through events. The microservices variant is documented in
SMARTSTAFF_BACKEND_DESIGN.md §3 if we ever split into *N* services.

### The layered request path (learn it once, it repeats everywhere)

```
HTTP request
   |
controller/     thin: validates @Valid DTO, calls ONE service method, returns a DTO
   |
service/        interface (the contract)
service/impl/   the business logic lives here
   |
repository/     Spring Data JPA
   |
entity/         @Entity -- never leaves the service layer
```

Supporting layers:

- `dto/request` + `dto/response` — the API contract, **fixed by the frontend**
  (field names, nullability must match what the React app expects).
- `mapper/` — entity <-> DTO conversion (keeps JPA details out of the API).
- `exception/` — `ApiException` thrown anywhere; `GlobalExceptionHandler` is the one
  place that turns it into the frontend's `{ok:false, message, code}` shape.
- `security/` — `JwtService` (issue/validate), `JwtAuthFilter` (resolve token to a
  User, reject un-approved employees), `JobAccessGuard` (per-job ownership).
- `filter/` — `RequestIdFilter` (request id + access log, masks invite tokens),
  `RateLimitFilter` (per-client limits on login/signup + interview-link endpoints).
- `config/` — `SecurityConfig`, `DemoDataSeeder` (seeds the two demo accounts),
  `ProductionSecretsGuard` (refuses to boot in `prod` with dev-default secrets).
- `client/` — `GeminiClient` + `TwilioClient` are the **only** classes that call
  external APIs (timeouts set, base URLs configurable so tests swap in a stub).
- `util/` — stateless helpers: `TextExtractor` (Apache Tika), `SkillDictionary`
  (skills.txt matching), `FileStorageService` (disk + SHA-256), `CryptoService`
  (AES-256-GCM for secrets at rest), `ExperienceParser`, `QuestionBankFileParser`,
  `TwilioSignatureValidator`.

The 6 layer rules in [FOLDER_STRUCTURE.md](FOLDER_STRUCTURE.md) answer every "where
does this code go?" question — read them verbatim.

---

## Layer 4 — The 7 modules (the feature map)

Learn the codebase as 7 modules. Each has its own controller + service(+impl) +
entities, all under `src/main/java/com/smartstaff/`.

| Module | Owns | Key endpoints |
|---|---|---|
| **auth** | users, roles, login, approval | `/api/auth/login`, `/me`, `/signup`, `/approve` |
| **jobs** | JDs, resumes, stored files | `/api/upload_jd`, `/upload_resumes`, `/jobs`, `/jobs/{id}` |
| **screening** | parsing, deterministic scoring, recruiter AI chat, reports | `/api/run_screening`, `/progress`, `/universal_execute`, `/download_report` |
| **assessment** | question generation, question bank, invites | `/api/assessment/generate`, `/assessment/answer_key/{jobId}`, `/invites/mint` |
| **interview** | browser + phone AI interviews, transcripts | `/api/interview/prepare`, `/place_call`, `/by_token/{token}`, `/save_by_token/{token}` |
| **activity** | event feed for dashboard / notification bell | `/api/activity/{jobId}` |
| **settings** | admin-only integration config | `/api/config`, `/config/gemini`, `/config/twilio`, `/config/public_url` |

Full endpoint list with notes: SMARTSTAFF_BACKEND_DESIGN.md §4.

---

## Layer 5 — The data model & relationships

```
users ---1:N---> jobs (uploaded_by)
jobs  ---1:N---> resumes
jobs  ---1:N---> candidates ---1:1---> resume
jobs  ---1:N---> assessments ---1:N---> assessment_questions
jobs  ---1:N---> interviews  ---1:N---> interview_turns
jobs  ---1:N---> invites   (single-use; only the token HASH is stored, never the token)
question_bank_items ---> job_id NULLABLE   <- shared across ALL jobs (the many-to-many case)
```

- Invite tokens: store the **hash**, not the raw token. Consumed atomically with
  `UPDATE ... WHERE used_at IS NULL AND expires_at > now()`.
- `ON DELETE CASCADE` from `jobs` handles JD deletion in the monolith.

**Migrations** are Flyway, one file per change, never edit an applied one:

```
V1 auth  ->  V2 jobs/resumes  ->  V3 candidates  ->  V4 settings  ->
V5 question bank  ->  V6 assessments + invites  ->  V7 interviews  ->  V8 interview calls
```

`V9__interview_media.sql` (video answers) is **designed but not built** — see
[INTERVIEW_CHANNELS_DESIGN.md](INTERVIEW_CHANNELS_DESIGN.md).

Full column list: FEATURES.md "Data model so far" and SMARTSTAFF_BACKEND_DESIGN.md §5.

---

## Day-one checklist for the new joiner

1. **Read**, in order: ROLES_AND_WORKFLOW.md (the story) -> FOLDER_STRUCTURE.md
   (the layers) -> SMARTSTAFF_BACKEND_DESIGN.md (the why).
2. **Run it:** `docker-compose up` (isolated Postgres on port 15433), start the app
   on port 8000, log in with the seeded demo accounts:
   - `admin@smartstaff.demo` / `AdminDemo@123`
   - `EMP1001` / `EmployeeDemo@123`
3. **Trace one request end-to-end**, e.g. `POST /api/upload_jd`:
   `JobController` -> `JobService`/`JobServiceImpl` -> `TextExtractor` +
   `SkillDictionary` -> `JobRepository` -> `Job` entity -> `JobMapper` -> response
   DTO. Once one path is clear, the other 6 modules are the same shape.
4. **Run the tests:** `mvn test` (242 tests; Docker must be running). The
   `src/test/java/com/smartstaff/integration/` folder is the best live documentation
   — one class per feature area, real Postgres + MockMvc.
5. **Note the guardrails:** deterministic scoring; secrets encrypted (AES-256-GCM);
   single-use hashed invite tokens; per-job `JobAccessGuard`; rate limiting on login
   and public token endpoints; Twilio webhook signature validation;
   `ProductionSecretsGuard` refuses dev secrets under the `prod` profile.

---

## What is NOT built yet (so nobody wastes time looking)

- **Candidate-facing assessment page** — links are minted, but there's no page to
  take a test, no marking, no results/scorecard.
- **Video interview** (camera recording) — designed only (V9), not implemented.
- **Real Twilio / Gemini runs** — code paths exist and are tested against in-JVM
  stubs; never run against real accounts.
- **Email sending** — by design; the backend only prepares `mailto:` links.
- **Candidate stage / notes** — currently kept in the recruiter's browser
  (localStorage), not on the server.

See [FEATURE_STATUS.md](FEATURE_STATUS.md) and ROLES_AND_WORKFLOW.md §7–8 for the
authoritative list of gaps and known security caveats.
