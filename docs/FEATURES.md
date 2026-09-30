  # backend-classic — Features & Build Status

Backend for **`smartstaff/frontend`** (see that app's own `FEATURES.md` for the full
end-user feature list this backend exists to serve). Built from
`SMARTSTAFF_BACKEND_DESIGN.md`, placed at `smartstaff/backend-classic/` — deliberately
separate from `smartstaff/backend/` (a different Java backend, for `frontend-v2`) to avoid
any table-name or port collision. Two unrelated backends now live side by side in this repo;
this document only covers `backend-classic`.

## Tech stack (as built)

| Layer | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 3.3.5 (Web, Security, Data JPA, Validation) |
| Architecture | Standard layered MVC (`controller → service/impl → repository → entity`, + `dto`/`mapper`/`exception`/`security`/`config`/`util`) — see `FOLDER_STRUCTURE.md` |
| Database | PostgreSQL 16, own Docker container, host port **15433** |
| Migrations | Flyway |
| Auth | Spring Security (stateless) + JWT (jjwt 0.12.6), BCrypt password hashing |
| JD/resume text extraction | Apache Tika (targeted PDF/Microsoft/text modules only) |
| Skill matching | Deterministic dictionary match (`skills.txt`, ~150 terms), word-boundary regex, clause-scoped must-have/nice-to-have classification — no LLM |
| File storage | Local disk (`./storage`, configurable via `STORAGE_DIR`) |
| External APIs | `client/GeminiClient` and `client/TwilioClient` — the only classes that call Google Gemini / Twilio; timeouts set, base URLs configurable |
| Monitoring | Spring Boot Actuator (`/actuator/health` + liveness/readiness), request-id logging |
| Tests | JUnit 5, Spring Boot Test + MockMvc, Testcontainers (real PostgreSQL 16), an in-JVM stub server standing in for Gemini and Twilio — 242 tests, see "Automated tests" below |
| Build | Maven (`pom.xml`, Spring Boot parent) |
| Local run | `docker compose up -d` (Postgres) + `mvn spring-boot:run` or the packaged jar |

> **Looking for a plain "is it complete?" checklist?** See [FEATURE_STATUS.md](FEATURE_STATUS.md) — every feature marked complete / partly done / not built.

Server listens on **port 8000** — the exact port `smartstaff/frontend`'s `VITE_API_BASE`
fallback expects in dev, so the frontend needs zero configuration to talk to this backend.

## Build status by phase (per SMARTSTAFF_BACKEND_DESIGN.md §10)

| Phase | Scope | Status |
|---|---|---|
| 0 | Project setup, Docker Compose, Flyway, error format, CORS | ✅ Done |
| 1 | `auth` + seed users | ✅ Done, verified end-to-end (curl + real frontend login) |
| 2 | `jobs` + file storage | ✅ Done, verified end-to-end (curl + real frontend Jobs page) |
| 3 | `screening` (parsing + deterministic scoring + progress) | ✅ Done, verified end-to-end (curl + real frontend Candidates page) |
| 4 | `activity` | 🚧 Minimal read-only version pulled forward in Phase 3 (see below) — no dedicated event table yet |
| 5 | `settings` + AI chat | ✅ Done. Config (Gemini/Twilio/public URL/invite TTL/Piston) + question bank upload (CSV/JSON/XLSX) + `/api/universal_execute` (recruiter chat, Gemini function-calling) all built and verified end-to-end (curl + real frontend chat) |
| 6 | `assessment` + invites + question bank + scorecards | 🚧 Generation (AI/Mix/Custom), status, answer key, and invite minting done and verified end-to-end (curl + real frontend Candidates page) — question bank storage was already done in Phase 5. The candidate-facing assessment-taking flow (F6: `by_token`/`save_by_token`/`submit_by_token`/`run_by_token`) is built and tested, and `submissions` now returns real attempts. Automatic scoring (F9) is built: on submit an attempt is graded in the background (deterministic MCQ/MSQ/LOGIC/CODE_OUTPUT, coding tests in the sandbox, CODE_WRITE rubric + SCENARIO via Gemini), exposed at `GET /api/assessment/scorecard/{jobId}/{attemptId}` with `POST .../rescore/...`. The scorecard PDF export (7.11) is built too (`GET /api/scorecard/{jobId}/{attemptId}`). **The remaining gap is the frontend candidate-taking page and results/scorecard UI.** |
| 7 | `interview` (self-service + browser) | ✅ Done. `config`/`prepare`/`invites/mint`/`save`/`by_token`/`save_by_token`/`transcripts` all built and verified end-to-end (curl + real frontend, including the candidate-facing `/interview/:token` welcome screen and error states). Twilio phone calls (`place_call`/`call_status`) are Phase 8, not this phase |
| 8 | Twilio phone interviews | ✅ Built and verified with simulated Twilio requests: `place_call` (a real call to Twilio's API, correctly rejected for the test credentials), the voice / answer / status webhooks with `X-Twilio-Signature` validation (checked against Twilio's own published example), and `call_status`. **No real phone call has been placed** — that needs a real Twilio account and a public tunnel |
| 9 | Hardening (tests, rate limits, observability) | 🚧 In progress. Done: per-job ownership checks on every job-scoped endpoint, rate limiting, request IDs + safe logging, health probes, production secrets guard, revoked-employee lockout, timeouts on external calls, and 144 automated tests (unit + integration on a real PostgreSQL). Still to add: tests for chat / settings / rate limiting |

## What works right now (Phases 0-3, 5, 6 minus scorecards/results, 7, 8 — Phase 9 in progress)

### Auth (`/api/auth/**`)

- **`POST /api/auth/login`** — `{role, identifier, password}`. Admin logs in by email,
  Employee by Employee ID. Returns `{ok, token, account}`; wrong password → 401; a
  not-yet-approved employee → 403 with `code: "pending_approval"` (matches the frontend's
  dedicated handling of that code).
- **`GET /api/auth/me`** — validates the bearer token, returns the current account, and
  bumps `last_seen_at` (drives the Employees page's online/offline dot). Invalid/missing
  token → 401.
- **`POST /api/auth/signup`** — admin signup (`role:"admin"`) always succeeds immediately.
  Employee signup (`role:"user"`) is **pending by default**: no token is returned, only
  `{ok, pending_approval:true, message, account}` — *unless* the request carries a valid
  admin's bearer token (i.e. an admin creating an employee from the Employees page), in
  which case it's auto-approved and a token is returned immediately.
- **`POST /api/auth/logout`** — always `{ok:true}` (stateless JWTs, nothing to revoke
  server-side yet — no denylist in v1).
- **`GET /api/auth/accounts`** *(admin only)* — `{ok, admins:[...], users:[...]}`, each
  account including `approved`/`last_seen_at`/`last_login_at`/`created_at`.
- **`POST /api/auth/approve`** *(admin only)* — `{employee_id, approved}` → sets that
  employee's status to APPROVED or REJECTED.

### Jobs (`/api/upload_jd`, `/api/upload_jd_skills`, `/api/upload_resumes`, `/api/jobs/**`, `/api/jd/**`, `/api/resumes/**`)

- **`POST /api/upload_jd`** *(multipart: `file`, optional `force=1`)* — extracts text via
  Tika, runs deterministic skill extraction + must-have/nice-to-have classification,
  detects a duplicate upload (same uploader + identical file content) and asks for
  confirmation (`status:"duplicate"`) unless `force=1` is set. On success, mints a
  sequential `JD-0001`-style number and returns `{status:"success", job_id, jd_title,
  jd_number_display, suggested_reply, evicted_jobs?}`.
- **`POST /api/upload_jd_skills`** — same flow without a file: `{skills: "comma or
  newline separated", title}` → up to 30 deduplicated skills, all must-have (no wording
  to classify against).
- **Skill classification heuristic** (`SkillDictionary.classify`, no LLM): a skill is
  nice-to-have if words like "preferred", "nice to have", "bonus", or "a plus" appear in
  the *same sentence/clause* as the mention (clause-bounded by `. ; \n` so a qualifier in
  one sentence can't leak onto a skill in an unrelated, nearby one) — otherwise must-have.
- **`POST /api/upload_resumes`** *(multipart: `files[]`, `session_id`=job UUID)* — stores
  each file, best-effort text-extracts it (a corrupt file doesn't fail the whole batch),
  and de-duplicates filenames within a job (`resume.pdf` → `resume (2).pdf`) since
  `Candidates.jsx`/`Jobs.jsx` build resume download URLs directly from the filename.
- **`GET /api/jobs`** — **admins see every job; employees see only jobs they uploaded**
  (resolves design-doc open question #1). Each row includes skills, skill count, owner
  info, and JD number.
- **`GET /api/jobs/{id}`** — full detail: JD text, `jd_struct` (critical/important skills,
  experience range extracted from JD wording via `ExperienceParser`), real `candidates`
  (as of Phase 3, below), and still-placeholder empty `submissions`/`interviews`/
  `assessment_urls` (populated in Phases 6/7 — the frontend only reads their `.length`
  today, so empty arrays are a safe, contract-correct placeholder, not a bug).
- **`DELETE /api/jobs/{id}`** — admin can delete any job; an employee only their own
  (403 otherwise). Deletes on-disk JD + resume files, then the DB rows (cascade).
- **`GET /api/jd/{id}/resumes`**, **`GET /api/jd/{id}/download`** (falls back to a
  plain-text export if no original file — e.g. a skills-only JD),
  **`GET /api/resumes/{jobId}/download/{fileName}`** — all verified serving real bytes
  with correct `Content-Type`/`Content-Disposition`.
- **FIFO eviction at 100 jobs** (`app.jobs.max-retained`): after every successful upload,
  the oldest jobs beyond the cap (files included) are deleted and reported back as
  `evicted_jobs` — matches `VoiceScreening.jsx`'s eviction-notice bubble.

### Screening (`/api/run_screening`, `/api/progress`, `/api/download_report`)

- **`POST /api/run_screening`** `{session_id: jobId}` — deterministic (no LLM) scoring of
  every resume on the job:
  - Skill match via the same `SkillDictionary` used for JD skill extraction (so resume
    and JD skills are matched by the exact same logic) — must-have skills are worth up to
    85 points, nice-to-have up to 15; an experience bonus/penalty of ±5 applies if the JD
    states a minimum years requirement.
  - **Years of experience**: max of every "N years [of experience]" mention found in the
    resume text (`ExperienceParser.extractYearsOfExperience`).
  - **Candidate name**: the first non-blank line of the resume if it looks like a plain
    name (2–4 capitalized words), else a cleaned-up filename.
  - **Email/phone**: first regex match in the resume text.
  - Re-running screening for a job **deletes and recreates** every candidate row, so
    editing the JD or adding more resumes and re-screening always reflects the latest
    state.
  - Response field names (`File_Name`, `Candidate_Name`, `Fit_Score_Out_Of_100`, ...) are
    kept exactly as the original app's own convention — see
    `dto/response/CandidateRowResponse.java` — so the frontend needs zero changes.
- **`GET /api/progress`** — always `{log: null}`. Scoring here is synchronous and fast (no
  LLM in the loop, unlike the original app), so there's no real background job to poll;
  the frontend just shows no live line while waiting, which is harmless.
- **`GET /api/download_report`** — CSV (not true `.xlsx`; Apache POI was in scope for a
  real Excel export but wasn't needed to unblock this) of every candidate the requester
  can see, admin-vs-employee scoped the same way as `GET /api/jobs`. **This endpoint is
  public** (see the Security section below) since the frontend links to it with a plain
  `<a href target="_blank">`, not axios.
- **Candidates now appear in `GET /api/jobs/{id}`**'s `candidates` array (real rows,
  sorted by fit score descending) — this is what makes the Candidates page render actual
  data instead of "Your ATS is empty".
- **Minimal Phase 4/6 stubs pulled forward**, because `Candidates.jsx`'s `fetchSubmissions`
  calls three endpoints with `Promise.all` and only one of the three has its own
  `.catch()` — a 404 from either of the other two silently aborted the whole fetch and
  hid the real Phase 3 candidate data behind an empty state:
  - **`GET /api/assessment/submissions/{jobId}`** → `{submissions: [], pass_threshold: 50}`
    (real Phase 6 scope — this just reports "zero submissions," which is true).
  - **`GET /api/assessment/status/{jobId}`** → `{has_jd, jd_title, num_questions: 0,
    num_submissions: 0}`.
  - **`GET /api/activity/{jobId}?limit=n`** → real events (not fake data!) *derived* from
    existing `Job`/`Resume`/`Candidate` timestamps — no dedicated `activity_events` table
    yet (that's the real Phase 4 scope), but the Dashboard sparkline, Analytics timeline,
    and notification bell all render genuine activity as a result.

### Recruiter AI chat (`POST /api/universal_execute`)

`VoiceScreening.jsx`'s chat/voice assistant. Genuinely generic, not hardcoded to one
flow: the request's `platform_config.persona` becomes the Gemini system instruction, and
each entry in `platform_config.available_tools` (`{tag_name, description,
expected_params}` — a bare, comma-separated parameter-name list rather than a schema)
becomes a real Gemini function declaration (`tools: [{functionDeclarations: [...]}]`)
against `models/gemini-3.5-flash:generateContent`.

- If Gemini's response is plain text, it's returned as `{reply, table_data: null}`.
- If Gemini calls a function named `PROCESS_RESUMES` (the only tool the frontend ever
  declares, and the only one this backend knows how to execute) — delegates straight to
  `ScreeningService.runScreening(jobId)` and returns *that* response verbatim. Both
  endpoints share one response shape (`{reply, table_data}` —
  `dto/response/RunScreeningResponse.java`), so `universal_execute` has no response DTO
  of its own.
- If Gemini calls some other, unrecognized function name (a tool the caller declared but
  this backend has no handler for), falls back to whatever plain-text part (if any)
  accompanied the call.
- `session_id` can be the frontend's `'local_react_user'` legacy placeholder (no job
  selected yet) — handled as a normal conversational case (a reply asking to upload a JD
  first), not a validation error, since `session_id` isn't `@NotBlank` and a bad/legacy
  value just resolves to "no active job" rather than a parse failure.
- **No Gemini key, a rejected key, or a network failure all come back as a normal `200`**
  with an apologetic `reply` — this is a chat endpoint, so nothing here throws an HTTP
  error for an AI-side failure. Verified for real: a real (intentionally invalid) key
  gets a genuine `400 API_KEY_INVALID` from Google, logged as a `WARN`, and the chat
  still receives "I couldn't reach the AI service just now" — confirmed with curl *and*
  by sending a real message through `VoiceScreening.jsx`'s chat box in the browser.
- **Not verified live**: an actual successful `PROCESS_RESUMES` function call from
  Gemini (needs a real, working Gemini key — same caveat as Phase 6's AI question
  generation). The request-building and response-parsing code path is exercised up to
  Google's auth check (a malformed request would fail differently, before the key is
  even checked), and the dispatch-to-`runScreening` logic is a straight delegation to
  code already verified in the Screening section above.

### Settings / config (`/api/config`, `/api/config/*`, `/api/reset`)

All GET/POST `/api/config*` and `POST /api/reset` are **admin-only**
(`@PreAuthorize("hasRole('ADMIN')")`) — matches `Settings.jsx` living behind
`ProtectedRoute requireAdmin`. Backed by one `app_settings` key/value table
(`V4__settings.sql`); Gemini API key and Twilio auth token are AES-256-GCM
encrypted at rest (`CryptoService`) and only ever returned as a masked
preview (`AIza…7890`) — never sent back to the browser in full, matching
the design doc's §6 requirement.

- **`GET /api/config`** — the whole config snapshot Settings.jsx renders:
  Gemini (configured/has_key/model/masked preview), public URL (value +
  whether it's a localhost URL), Twilio (configured + masked SID/token +
  from-number), code runner (Piston URL/enabled/detected languages),
  invite TTL (seconds/hours).
- **`POST /api/config/gemini`** `{api_key, persist}` — saves (encrypted) or,
  on an empty key, clears it.
- **`POST /api/config/gemini/test`** — makes a **real call** to
  `generativelanguage.googleapis.com` (list-models, doesn't burn a
  generation) to verify the stored key actually works, and picks a
  `*-flash` model name back out of the response. Verified against a real
  invalid key → Gemini's own "API key not valid" error surfaces correctly
  in the UI.
- **`POST /api/config/public_url`** — stores the backend's externally-
  reachable base URL (for candidate-facing links/Twilio webhooks later);
  flags whether it's still a localhost URL.
- **`POST /api/config/invite_ttl`** — default validity window for future
  invite links (used by Phase 6).
- **`POST /api/config/twilio`** `{account_sid, auth_token, from_number}` —
  saves credentials. **Deviates from the original app**: a blank field
  *leaves the existing value alone* instead of clearing it (see
  `TwilioConfigRequest`'s javadoc) — the original behavior would silently
  wipe a working credential if a recruiter only meant to update one field.
- **`POST /api/config/twilio/test`** — **real call** to
  `api.twilio.com/2010-04-01/Accounts/{sid}.json` with Basic Auth. Verified
  against fake credentials → Twilio's real 401 "invalid username" surfaces
  correctly in the UI.
- **`POST /api/config/piston_url`** — saves the URL and **probes it live**
  (`GET {url}/runtimes`) to populate the detected-languages list; an empty
  or unreachable URL falls back to `piston_enabled:false` with `["python"]`
  (matches the "local subprocess: Python is guaranteed" messaging).
- **`POST /api/reset`** — intentionally a **no-op**. The original app used
  this to wipe its one global chat session; this backend has no equivalent
  global session (everything is job-scoped and already individually
  deletable via `DELETE /api/jobs/{id}`), so there's nothing safe to wipe
  here without being needlessly destructive.
### Question bank (`/api/questions/**`)

Shared across all jobs (no per-JD scoping) — Phase 6's assessment generator will
draw from this pool. Backed by `V5__question_bank.sql`
(`question_bank_uploads` + `question_bank_items`).

- **`POST /api/questions/upload`** *(admin only, multipart `file`)* — accepts
  `.csv`, `.json`, or `.xlsx` (all three verified with real files, including
  a real POI-generated `.xlsx`). A hand-rolled RFC4180-ish CSV reader handles
  quoted fields so question text containing commas doesn't break column
  alignment. Each row/object needs `type` (mcq/msq/descriptive/coding) and
  `question`; everything else is optional. **Bad rows are skipped with a
  warning, not a failed upload** — verified with a mixed file (unknown type,
  MCQ with no options) that correctly added the 3 good rows and reported 2
  warnings.
  - `correct_index` accepts a 0-based number *or* a letter (`B` → index 1),
    and for MSQ, several comma/pipe-separated values (`"0,1,3"`).
  - `options` accepts a pipe-separated string (CSV/XLSX) or a JSON array.
- **`GET /api/questions/bank?limit=1`** — stats (`total`, `by_type`,
  `by_level`, upload count) plus the upload list Settings.jsx's "Recent
  uploads" panel renders.
- **`DELETE /api/questions/upload/{id}`** *(admin only)* — deletes that
  upload's questions (cascade).
- **`POST /api/questions/clear`** *(admin only)* — wipes the whole bank.

### Assessments & invites (`/api/assessment/**`, `/api/invites/mint`)

Backed by `V6__assessments.sql` (`assessments`, `assessment_questions`, `invites`).
A job has at most one assessment (`assessments.job_id` is `UNIQUE`) spanning all three
levels (L1/L2/L3) at once — generating again replaces it wholesale rather than
merging into the existing one.

- **`POST /api/assessment/generate`** — body `{session_id, question_source}` where
  `session_id` is the job's UUID (Candidates.jsx's naming, not a separate session
  concept) and `question_source` is `ai` / `mix` / `custom`. For each level, builds up
  to 6 questions (`QUESTIONS_PER_LEVEL`):
  - **`custom`** — pulls up to 6 from the question bank (items tagged for that level,
    plus level-agnostic items), then **pads any shortfall with Gemini** if a key is
    configured — matches the "Your bank; AI only pads if short" copy in
    Candidates.jsx's source-picker.
  - **`mix`** — pulls up to 3 from the bank, tops up the rest (typically 3, more if the
    bank came up short) via Gemini.
  - **`ai`** — all 6 from Gemini; fails outright (before calling Gemini at all) if no
    key is configured, with a clear message rather than a confusing empty result.
  - Gemini is called once per level (not once per question) via
    `models/gemini-3.5-flash:generateContent` with `responseMimeType: application/json`,
    prompted with the job title/skills/JD excerpt and asked for a strict JSON array of
    `{type, question, options, correct_indices, skill, difficulty}`. **A failed or
    rejected Gemini call (bad key, network error, malformed JSON) never fails the
    request** — it's logged as a `WARN` and that level just falls back to whatever the
    bank supplied. Verified with a real (intentionally invalid) Gemini key: the API
    genuinely rejects it (`400 API_KEY_INVALID`), and generation still returns
    `"status":"success"` using bank-only questions.
  - Returns `{status:"success", assessment_url, assessment_urls:{L1,L2,L3},
    counts:{L1,L2,L3}}`, or `{status:"error", message}` if every level came up with
    zero questions (e.g. `ai` with no key and no bank fallback).
  - **Runs synchronously**, not on a background thread — see the deviation below.
- **`GET /api/assessment/status/{jobId}`** — `ready`/`generating`/`error` plus
  `num_questions`/`jd_title`/`assessment_url`. Since generation is synchronous, `ready`
  is always `true` and `generating` always `false` by the time this is ever polled;
  the fields exist because `Candidates.jsx`'s poll loop after `generate` checks them
  unconditionally regardless.
- **`GET /api/assessment/answer_key/{jobId}`** — `{role_title, levels:[{level, count,
  questions}]}`, grouped by level; MCQ questions carry `correct_index`, MSQ carry
  `correct_indices`, CODING/DESCRIPTIVE carry neither (both fields omitted via
  `@JsonInclude(NON_NULL)`, matching what `AnswerKeyQuestion` in Candidates.jsx expects
  per type). Verified against a real generated assessment covering all four question
  types across two levels.
- **`POST /api/invites/mint`** — body `{session_id, candidate_email, candidate_name,
  levels:[...], combined}`. Mints one single-use, expiring link per level, or one link
  covering every listed level when `combined:true` — matching Candidates.jsx's two
  call sites (per-level mint on modal open, combined mint once 2+ levels are ticked).
  Only the **SHA-256 hash** of each token is stored (`FileStorageService.sha256Hex`),
  per the design doc's "store invite token hashes, not raw tokens." TTL comes from
  `Settings → Invite link expiry` (`SettingsService.getInviteTtlSeconds()`, default 48h).
  See the deviation below re: token "coalescing".

### Interviews (`/api/interview/**`)

Self-service and browser-mode AI L1 interviews — `V7__interviews.sql` (`interviews`,
`interview_turns`, and an `interview_id` column added onto V6's `invites` table so a
self-service link can point at the specific interview it should serve). Twilio phone
calls (`place_call`, `call_status`, the TwiML webhooks) are described in the next section.

- **`GET /api/interview/config`** — `{twilio_configured, from_number}`, read off the
  same Settings values `/api/config` reports (`SettingsService.isTwilioConfigured()` /
  `getTwilioFromNumberOrNull()`). Drives `PhoneConfirmModal`'s "phone call" option.
- **`POST /api/interview/prepare`** — `{session_id, candidate_name, phone, file_name,
  language}`. Looks the candidate up by `(job_id, resume filename)`
  (`CandidateRepository.findByJobIdAndResumeFilename`, matching the frontend, which only
  ever sends a filename, not a candidate id) to ground the interview in their actual
  résumé — matched skills and years of experience, when found. Calls Gemini once for
  `{intro, outro, questions:[{category, skill, question}]}` (5 questions spanning
  background/technical/behavioral/closing, written in whatever language the caller
  asked for), persists a `PENDING` `Interview` + its `InterviewTurn`s, and returns them.
  **Throws an HTTP error** (502 if Gemini itself is unreachable/rejected, 400 for no key
  or a bad session) rather than a graceful 200 — `InterviewRoom.jsx`'s prepare handler
  only has an HTTP-error catch path, unlike the invite-mint flow below.
- **`POST /api/interview/invites/mint`** — `{session_id, candidate_email,
  candidate_name, phone, file_name, language}`. Runs the *exact same* internal prep as
  above (mode `SELF` instead of `BROWSER`), then mints a single-use, hashed-token invite
  pointing at the prepared interview. Unlike `/prepare`, a prep failure here comes back
  as a normal `{status:"error", message}` **200**, matching Candidates.jsx's
  `PhoneConfirmModal` "candidate_link" handler, which explicitly expects the "prep step's
  own JSONResponse error path" rather than an HTTP error. Link shape: `{public_base_url}
  /interview/{token}` — the token alone resolves everything server-side, no job number or
  level needed in the URL (unlike assessment links).
- **`POST /api/interview/save`** — HR-side save of a completed browser-mode session
  (`{session_id, interview_id, transcript:[...], started_at, ended_at, ...}`). Verifies
  the interview belongs to that job, replaces its turns wholesale from the posted
  transcript (same "delete and recreate" pattern as screening/assessment generation),
  and marks it `COMPLETED`.
- **`GET /api/interview/by_token/{token}`** and **`POST /api/interview/save_by_token/{token}`**
  — public (`CandidateInterview.jsx`'s `/interview/:token` route, already `permitAll` in
  `SecurityConfig` since Phase 0). `by_token` looks up the invite by
  `SHA-256(token)` and rejects (400, human-readable message) an unknown, wrong-kind,
  expired, or already-used token *without* consuming it — a page refresh mid-interview
  doesn't burn the link. `save_by_token` is what actually **atomically consumes** the
  token: one `UPDATE ... WHERE used_at IS NULL AND expires_at > now()`
  (`InviteRepository.consume`) that returns 0 affected rows if the link was already used
  or just expired — including a concurrent duplicate submission racing this one — so
  "already completed" is reported correctly either way, then saves the transcript the
  same way `/save` does.
- **`GET /api/interview/transcripts/{jobId}`** — `{interviews:[{interview_id,
  candidate_name, phone, role_title, transcript, saved_at}]}`, only `COMPLETED`
  interviews (a `PENDING` one — invite minted, not yet taken — has no transcript worth
  showing). Feeds Candidates.jsx's "L1 Interview Transcripts" table and transcript-view
  modal.
- **Verified end-to-end**: `config`, `prepare`'s validation and its real (and
  correctly-rejected) Gemini call, `invites/mint`'s graceful `{status:"error"}` path, and
  — since there's no working Gemini key to carry a `prepare`/`mint` call all the way to
  success (see the deviation below) — the entire rest of the pipeline verified by
  seeding a `PENDING` interview directly in Postgres (identical shape to what a
  successful `prepare` would have written) and driving it through the real HTTP
  endpoints: `by_token` → `save_by_token` (consumes) → `by_token` again (correctly now
  "already completed") → `save_by_token` again (correctly rejected, 0 rows consumed) →
  `transcripts` (shows the saved transcript). Also confirmed the HR-direct `/save` path
  and its job/interview mismatch check the same way. All of this rendered correctly in
  the real frontend too: the Candidates page's transcript table and view modal, and —
  most convincingly — `CandidateInterview.jsx`'s actual welcome screen at
  `/interview/{token}`, showing the right candidate name, role title, question count,
  and localized language label straight from the seeded data.

### Phone interviews via Twilio (`/api/interview/place_call`, `/call_status`, `/twiml/**`)

`V8__interview_calls.sql` adds `interviews.twilio_call_status` — Twilio's own, finer-grained
status string (`queued`, `ringing`, `in-progress`, `completed`, `busy`, `no-answer`, `failed`,
`canceled`), which `InterviewRoom.jsx`'s `PhoneCallRoom` shows as-is; the V7 `status` stays the
three-state `PENDING`/`COMPLETED`/`FAILED`. All Twilio traffic goes through `client/TwilioClient`
(HTTP Basic auth with the account SID + auth token; base URL configurable through
`app.twilio.api-base-url`, which is how the tests point it at a stub).

**How a call runs.** HR first prepares the interview (the same `POST /api/interview/prepare` a
browser interview uses), then:

1. **`POST /api/interview/place_call`** `{session_id, interview_id, phone}` asks Twilio to dial
   the candidate, handing it two URLs on *our* public address (Settings → public URL): a voice
   URL and a status-callback URL. Returns `{status:"ok", call_sid}` — or, as a normal `200`
   (a call failing is an ordinary outcome `PhoneCallRoom` already handles),
   `{status:"error", detail}` for: Twilio not configured, unknown interview, or Twilio rejecting
   the request. On success the interview becomes `mode=PHONE` and stores the call SID and
   Twilio's first status. The other fields the frontend re-sends (name, questions, intro…) are
   ignored on purpose — the interview row `prepare` created already has them.
2. When the candidate answers, Twilio requests **`POST /api/interview/twiml/voice/{interviewId}`**.
   The backend answers with TwiML: `<Say>` the intro, then a `<Gather input="speech">` that asks
   question 1 and listens.
3. After each spoken answer Twilio posts **`/twiml/answer/{interviewId}/{seq}`** with the
   recognised speech. The backend saves it **immediately** (a phone interview has no browser to
   send a final transcript, so answers are stored one by one as they arrive) and replies with the
   next question's `<Gather>` — or, after the last one, the outro and `<Hangup/>`, marking the
   interview `COMPLETED`. Silence is stored as an empty answer and the call moves on. An
   out-of-range question number just ends the call politely; an unknown interview says sorry and
   hangs up (still valid TwiML, never an error page). All spoken text is XML-escaped.
4. Twilio posts progress to **`/twiml/status`** (initiated / ringing / answered / completed): the
   backend stores it as `twilio_call_status`. `completed` → interview `COMPLETED` (a caller who
   hangs up early also lands here, and what was said is the transcript); `busy` / `failed` /
   `no-answer` / `canceled` → `FAILED` — but a later stray failure never downgrades an interview
   that already completed. A callback for a call we don't know is acknowledged and ignored.
5. **`GET /api/interview/call_status/{interviewId}`** — polled every 3 s by the call monitor:
   `{status, transcript, answered, total, ended}`. `status` is Twilio's string (`queued` until the
   first callback arrives); `ended` becomes true for `completed`/`busy`/`failed`/`no-answer`/
   `canceled`, which stops the poll. Answered questions are counted by non-blank answers.

**The webhooks are public but not open.** Twilio can't send a bearer token, so the three
`/api/interview/twiml/**` routes are `permitAll` in `SecurityConfig` and instead verify Twilio's
`X-Twilio-Signature` (`util/TwilioSignatureValidator`): `base64(HMAC-SHA1(authToken, url + every
POST parameter as key+value, sorted by key))`. The `url` is the **configured public URL + the
request path** — never Spring's own idea of the request URL, which behind a tunnel says
`localhost:8000` and would never match what Twilio signed. A wrong, missing, tampered or
wrong-URL signature (or Twilio not being configured) gets `403` with `<Reject/>`, and changes
nothing. Responses are `text/xml;charset=UTF-8` so Hindi/Tamil/etc. interview text arrives intact.
The validator is checked against Twilio's own published worked example (same token, URL and
parameters, same expected signature), which was also recomputed independently with `openssl`.

**What has and hasn't been verified.**
- Verified against the **real Twilio API**: `place_call` reaches Twilio with correct auth and is
  rejected with `20003 Authentication Error` for the placeholder credentials in Settings — proof
  that the URL, auth header and form body are accepted as well-formed.
- Verified with **automated tests** (`PhoneInterviewIntegrationTest`, 21 tests) against a stub
  Twilio and requests signed exactly as Twilio signs them: a successful call (credentials,
  numbers and both webhook URLs on the wire), Twilio rejecting, missing configuration, the full
  intro → questions → outro conversation with each answer stored, silence, XML escaping, UTF-8
  with real Hindi text, every kind of bad signature, the signature following the public-URL
  setting, and all status transitions including the no-downgrade rule.
- **Not verified: a real phone call.** That needs a real Twilio account (a trial account can only
  call numbers you've verified), a real Gemini key for `prepare`, and a public HTTPS tunnel such as
  ngrok. To try it: put the tunnel URL in Settings → public URL **exactly** as Twilio will reach
  it (`https://…`, no trailing slash — the signature is computed from it, and a mismatch is the
  most likely reason webhooks get refused), save the Twilio SID, token and from-number, then use
  the Interview → phone option on a candidate.

### Security

- Every request carries `Authorization: Bearer <jwt>`; `JwtAuthFilter` validates it and
  loads the `User` into the Spring Security context — no session state, no cookies.
- **Admin-only areas** (`/api/config/**`, `/api/reset`, `/api/auth/accounts`, `/api/auth/approve`,
  and question-bank uploads/deletes) are decided in `SecurityConfig`'s filter chain, so a non-admin
  is refused with a clean 403 *before* the request body is parsed or validated (the Phase 9 tests
  found that method security alone answered an employee's invalid body with 400). The
  `@PreAuthorize("hasRole('ADMIN')")` annotations on the controllers stay as a second lock.
- CORS restricted to the Vite dev origins (`http://localhost:5173`, `:4173`) via
  `app.cors.allowed-origins`.
- **File-download endpoints are deliberately public** (`GET /api/jd/*/download`,
  `/api/resumes/**`, `/api/download_report`, and later `/api/scorecard/**`) — the frontend
  opens all of these as plain `<a href target="_blank">` links (Jobs.jsx, Candidates.jsx,
  Dashboard.jsx, Settings.jsx), never through axios, so no bearer token ever reaches them.
  Requiring auth here would just break every download with a 401. Security instead relies
  on the path containing an unguessable job/resume UUID. **This was a real bug caught while
  testing Phase 3** — Phase 2's download endpoints were built behind auth and only ever
  verified with curl (which sends the header manually), so the browser-click case wasn't
  caught until now. Revisit with signed URLs if stricter access control matters later.
- All error responses — validation failures, bad credentials, 403s, 404s for endpoints
  not built yet, and unhandled exceptions — go through one `GlobalExceptionHandler` and
  come back as `{ok:false, message, code?}`, which is exactly what
  `smartstaff/frontend/src/lib/auth.jsx`'s `describeError()` already knows how to render.

- **Per-job ownership (Phase 9).** Admins can act on any job; an employee only on jobs they
  uploaded. Previously only *listing* and *deleting* checked this, so any logged-in employee who
  had another job's UUID could read its JD and candidates, run screening, generate assessments,
  mint invites for arbitrary email addresses, or read interview transcripts. `security/JobAccessGuard`
  (a `@jobAccess` bean used from `@PreAuthorize` SpEL) now guards all **17** job-scoped
  endpoints: job detail, resume list, resume upload, activity, assessment status / submissions /
  answer key / generate, assessment invite mint, screening, the recruiter chat, interview prepare /
  invite mint / save / transcripts / place_call / call_status. `place_call` checks the *interview's*
  job as well as the session, so a valid session can't be paired with someone else's interview.
  Deliberately lenient about things that don't exist — an unknown id, or the frontend's legacy
  non-UUID `local_react_user` session id, passes through so the service can give its usual 400/404
  (there is no owner to protect). 53 automated tests cover every endpoint for intruder / owner / admin.
- **A revoked employee is locked out immediately.** `JwtAuthFilter` now applies login's own rule
  (an employee must be `APPROVED`) on *every* request; before, un-approving someone left their
  existing token working until it expired (24 h).
- **Rate limiting** (`filter/RateLimitFilter`, in-memory, per client address, per minute): 10 requests
  to login/signup (one shared budget) and 30 to the interview-link endpoints (`by_token` /
  `save_by_token`, another shared budget) — `app.rate-limit.*`. Over the limit → `429` with
  `{ok:false, message, code:"rate_limited"}` and `Retry-After`. It sits inside the security chain
  right after CORS, so the 429 still carries CORS headers and the browser lets the frontend read it.
  The client is the socket's address; `X-Forwarded-For` is honoured only when
  `app.rate-limit.trust-forwarded-for=true` (set that only behind a proxy you control — otherwise
  a caller could dodge the limit by inventing the header; a test proves a forged header is ignored
  by default). Twilio's webhooks aren't limited (signature-gated, and one call is a legitimate burst).
- **Production secrets guard** (`config/ProductionSecretsGuard`): with `SPRING_PROFILES_ACTIVE=prod`
  the app refuses to start while `JWT_SECRET`, `APP_ENCRYPTION_KEY` or `POSTGRES_PASSWORD` is still a
  development default, or too short — otherwise a forgotten env var would ship a publicly known
  token-signing key. Not active in normal local runs or tests.
- **Secrets at rest:** the Gemini key and Twilio auth token are AES-256-GCM encrypted and only ever
  returned masked; invite tokens are stored only as SHA-256 hashes and consumed by one atomic
  `UPDATE … WHERE used_at IS NULL AND expires_at > now()`.
- **One error shape**, now `{ok:false, message, code?, detail}`. `detail` repeats `message` because
  the frontend was written against a FastAPI backend and `PhoneCallRoom` reads *only* `detail` (it
  used to show "Request failed with status code 502" for every phone-interview error).
- **Known gaps (found while documenting roles, not fixed yet):**
  1. **Admin sign-up is open.** `POST /api/auth/signup` with `role:"admin"` creates an admin and returns
     a login token with no authentication and no approval — the original app behaved this way and the
     frontend's sign-up page offers an "Admin" choice. Anyone who can reach the server can become an
     admin (settings, all data, approvals). Suggested fix: allow it only for a logged-in admin (or a
     one-time bootstrap when no admin exists).
  2. **`GET /api/download_report` is public and unscoped.** It is `permitAll` (the frontend opens it as a
     plain `<a href>`, which cannot send a bearer token), and `ScreeningServiceImpl.downloadReportCsv`
     treats "no requester" like an admin, so an anonymous request returns every candidate of every job
     (names, emails, phones, scores). The per-user scoping only applies when a token is present, which
     the link never sends. Fixing it properly needs the frontend to download with its token (or a signed,
     short-lived link).

### Operations & configuration (Phase 9)

- **Health:** `/actuator/health` (public, status only — no component details), plus
  `/actuator/health/liveness` and `/readiness`. Everything else under `/actuator` needs a login;
  only `health` and `info` are exposed at all. (Earlier the security config listed
  `/actuator/health` as public but the actuator wasn't a dependency, so it answered 404.)
- **Request ids and logs:** every request gets an `X-Request-Id` (the caller's, if it is a safe
  short `[A-Za-z0-9._-]` string, else a fresh one), echoed on the response and printed as `[id]` on
  every log line. One access-log line per request: `5xx` → WARN, `4xx` or slow (≥ 2 s) → INFO,
  otherwise DEBUG (the frontend polls constantly). Only the path is logged, never the query string
  or headers, and invite tokens in `/api/interview/by_token/{token}` are masked as `***`.
- **External calls have timeouts** (Gemini: 5 s connect / 60 s read; Twilio: 5 s / 15 s) instead of
  waiting forever, and `server.shutdown=graceful` lets in-flight requests finish on stop.
- **Configuration** — every setting has an environment-variable override:

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8000` | HTTP port |
| `DB_URL` / `DB_USERNAME` / `POSTGRES_PASSWORD` | `jdbc:postgresql://localhost:15433/smartstaff_classic` / `smartstaff_classic` / dev value | Database |
| `JWT_SECRET`, `JWT_TTL_MINUTES` | dev value, `1440` | Login-token signing key and lifetime |
| `APP_ENCRYPTION_KEY` | dev value | Encrypts the Gemini key / Twilio token at rest |
| `CORS_ORIGINS` | `http://localhost:5173,http://localhost:4173` | Allowed browser origins |
| `STORAGE_DIR` | `./storage` | Uploaded JDs and resumes |
| `GEMINI_BASE_URL`, `GEMINI_MODEL` | Google's API, `gemini-3.5-flash` | Point at a stub in tests/demos |
| `TWILIO_API_BASE_URL` | `https://api.twilio.com` | Same |
| `RATE_LIMIT_ENABLED`, `RATE_LIMIT_AUTH_PER_MINUTE`, `RATE_LIMIT_TOKEN_PER_MINUTE`, `RATE_LIMIT_TRUST_FORWARDED_FOR` | `true`, `10`, `30`, `false` | Rate limiting |
| `SPRING_PROFILES_ACTIVE` | *(unset)* | `prod` turns on the secrets guard |

### Seed data

On first boot, `DemoDataSeeder` inserts the two accounts the frontend's login screen's
"Demo Credentials" buttons already advertise — idempotent, safe to restart:

- **Admin:** `admin@viztalent.demo` / `AdminDemo@123`
- **Employee:** `EMP1001` / `EmployeeDemo@123`

### Data model so far

```sql
-- V1__auth.sql
users (id, role, name, email, employee_id, password_hash, status, last_login_at,
       last_seen_at, created_at)
  -- unique(email) where role='ADMIN'; unique(employee_id) where role='USER'

-- V2__jobs.sql
jobs (id, jd_number [seq], title, original_filename, file_path, jd_text, content_hash,
      must_have_skills [jsonb], nice_to_have_skills [jsonb], experience_min_years,
      experience_max_years, owner_id, owner_name, owner_email, owner_role, created_at)
resumes (id, job_id, filename, file_path, extracted_text, size_bytes, uploaded_at)

-- V3__screening.sql
candidates (id, job_id, resume_id [unique], candidate_name, email, phone,
            years_experience, fit_score, matched_skills [jsonb], missing_skills [jsonb],
            matched_required_count, total_required_count, score_breakdown, created_at)
  -- deleted and recreated wholesale each time POST /api/run_screening runs for a job

-- V4__settings.sql
app_settings (key [PK], value, updated_by, updated_at)
  -- one row per config key; gemini_api_key/twilio_auth_token values are AES-256-GCM
  -- encrypted (CryptoService) before being written here

-- V5__question_bank.sql
question_bank_uploads (id, filename, uploaded_by, item_count, created_at)
question_bank_items (id, upload_id, type, level, skill, difficulty, prompt,
                      options [jsonb], correct_indices [jsonb], created_at)

-- V6__assessments.sql
assessments (id, job_id [unique], source [AI|MIX|CUSTOM], ready, error, created_at)
assessment_questions (id, assessment_id, level [L1|L2|L3], type [MCQ|MSQ|CODING|
                       DESCRIPTIVE], prompt, options [jsonb], correct_indices [jsonb],
                       skill, difficulty, created_at)
invites (id, token_hash [unique, sha-256 of the raw token — never the token itself],
         kind [ASSESSMENT|INTERVIEW], job_id, interview_id [added in V7, INTERVIEW-only],
         candidate_email, candidate_name, levels [jsonb], combined, expires_at, used_at,
         created_by, created_at)
  -- used_at is set by InviteRepository.consume's atomic UPDATE — exercised for real by
  -- Phase 7's save_by_token (assessments still have no consuming endpoint — see the
  -- open questions below)

-- V7__interviews.sql
interviews (id, job_id, candidate_id [nullable, ON DELETE SET NULL], mode [SELF|BROWSER|
            PHONE], status [PENDING|COMPLETED|FAILED], language, role_title,
            candidate_name, phone, intro, outro, twilio_call_sid, twilio_call_status
            [added in V8 — Twilio's own finer-grained status string], started_at,
            ended_at, created_at)
interview_turns (id, interview_id, seq, category, skill, question, answer)
  -- replaced wholesale on every save/save_by_token, same pattern as candidates and
  -- assessment_questions elsewhere in this schema
```

## Bugs found and fixed while testing (not caught by unit-level checks — only by
## actually exercising the endpoints, curl first then the real frontend)

1. **`jd_number` never came back on the response.** `@Column(insertable=false,
   updatable=false)` on a DB-sequence-generated column means Hibernate never re-reads it
   after INSERT unless told to. Fixed with `@Generated(event = EventType.INSERT)`.
2. **Skill classification bled across sentences.** "Required: Docker, Linux. Kubernetes is
   a plus." was marking *Linux* as optional too, because the original heuristic used a
   fixed 60-character window that reached into the next sentence. Fixed by bounding the
   check to the same clause (up to the nearest `. ; \n`).
3. **Phone regex swallowed newlines.** `\s` in the character class matched across line
   breaks, so `"9876543210\n5 years..."` was captured as one phone number including a
   stray leading digit from the next sentence. Fixed by using a literal space (`\x20`)
   instead of `\s`.
4. **Candidate names were always filename-derived**, even though most resumes lead with
   the person's actual name. Added a first-line heuristic (2–4 capitalized words) that's
   tried before falling back to the filename.
5. **`LazyInitializationException` on `GET /api/jobs/{id}`** once real candidates existed
   — the mapping step ran outside a transaction, and `Candidate.resume` is a lazy
   `@ManyToOne`; accessing `resume.getFilename()` after the session closed threw. Fixed by
   adding `@Transactional(readOnly = true)` to `JobServiceImpl.getJobDetail`. (Note: `.getId()`
   on a lazy proxy doesn't need the session — only *other* field access does, which is why
   this didn't show up on `GET /api/jd/{id}/resumes`, which only ever reads `.getId()`.)
6. **File downloads 401'd for a real browser click** — see the Security section above.
   Caught only once testing moved from curl (auth header sent manually) to an actual
   frontend link click.
7. **`Candidates.jsx`'s own `Promise.all` hid working Phase 3 data** behind "Your ATS is
   empty" because two of its three parallel requests (Phase 6 endpoints) 404'd and only
   one of the three had a `.catch()`. Not a backend bug per se, but invisible without
   minimal stubs for those endpoints — see the Screening section above.
8. **Found, not fixed (frontend, out of this backend's scope): the Answer Key modal
   still shows "No assessment generated yet" even after a real assessment exists.**
   `GET /api/assessment/answer_key/{jobId}` correctly returns `{role_title, levels:[...]}`
   (verified with curl — real MCQ/MSQ/CODING/DESCRIPTIVE questions across two levels come
   back correctly), and `AnswerKeyModal` already knows how to render `data.levels` (see
   its "prefer the multi-level breakdown" comment). But the fetch handler that populates
   that state — `Candidates.jsx`'s `openAnswerKey` — only copies
   `res.data.role_title` and `res.data.questions` into `setAnswerKey(...)`; it never
   copies `res.data.levels`, so the modal always falls back to an empty flat list. One
   line would fix it: `setAnswerKey({ role_title: res.data.role_title || '', levels: res.data.levels || [], questions: res.data.questions || [] })`.
   Left alone since frontend files aren't in this build's scope — flagging here instead.
9. **Found in passing (frontend): `openAnswerKey` is a `useCallback` with an empty
   dependency array**, so it closes over whatever `activeJobId` was on first render and
   never picks up later job-switcher changes — clicking "Answer Key" after switching jobs
   via the dropdown can call the endpoint with a stale job id. Worked around during
   manual testing by setting `localStorage['geeky_ai_active_job']` before load; not fixed
   for the same reason as #8.


10. **Screening a job a second time failed with HTTP 500** (`duplicate key … ux_candidates_resume_id`).
    Re-screening deletes a job's candidate rows and re-inserts one per resume, but the derived
    `deleteByJobId` only *queued* the deletes, and Hibernate flushes queued INSERTs before queued
    DELETEs — so the new rows collided with the not-yet-deleted ones. This contradicted this very
    document's claim that re-screening "deletes and recreates every candidate row"; that claim was
    only ever true for a job's *first* screening, and it broke the frontend's "add more resumes, then
    screen again" flow. Fixed with a bulk `@Query` delete that runs immediately. Found by the Phase 9
    integration tests, then reproduced against the live server.
11. **Sending a self-service interview link failed with HTTP 500 — for every candidate.**
    `invites.levels` is `NOT NULL DEFAULT '[]'`, but Hibernate inserts an explicit `NULL` for an
    unset field (the default never applies), and interview invites have no levels. Nothing had ever
    exercised this: the placeholder Gemini key always failed *before* the invite was saved, and
    the interviews used for manual testing were inserted with hand-written SQL, which skips the
    very code path being tested. The tests' stand-in Gemini let `prepare` succeed and exposed it
    immediately. Fixed by defaulting `Invite.levels` to an empty list. (Lesson: seeding rows by SQL
    is not a substitute for running the code that creates them.)
12. **TwiML was sent as bare `text/xml`** with no charset, which leaves the encoding to the
    consumer's guess (RFC 3023 even defaults it to US-ASCII) — exactly what matters for Hindi/Tamil
    interviews. Now `text/xml;charset=UTF-8`, with a test that round-trips real Devanagari text.
13. **A dead guard in the Twilio status callback:** it compared `"completed"` with the upper-case
    `"COMPLETED"` status, so it never protected anything, while its comment described protection
    it didn't give. Rewritten, with a test that a late `failed` callback can't downgrade a
    completed interview.
14. **Hand-written JSON error responses had no charset**, so the `—` in the rate-limit message came
    out as `?`. (The 401/403 bodies in `SecurityConfig` had the same latent problem; both now set
    UTF-8.)
15. **Admin-only endpoints answered a non-admin's invalid request body with 400 instead of 403** —
    validation ran before method security. Admin areas are now also enforced in the filter chain.
16. **Any logged-in employee could read or act on another employee's job by id** (see
    "Per-job ownership" under Security) — and **17.** an un-approved employee's existing token kept
    working until it expired; **18.** `/actuator/health` 404'd despite being listed as public;
    **19.** the phone-call screen couldn't display any backend error message. All fixed in Phase 9.

## Deliberate deviations from the design doc

- **Location:** `smartstaff/backend-classic/` instead of `smartstaff/backend/` (the design
  doc's §8 suggestion), because that path is already a different, unrelated Java backend.
- **Package-by-layer, not package-by-module.** The design doc's §8 structure groups code
  by feature (`auth/api`, `auth/web`, `auth/domain`, `jobs/...`); this build instead uses
  one flat layered structure shared by every feature (`controller/`, `service/` +
  `service/impl/`, `repository/`, `entity/`, `dto/request` + `dto/response`, `mapper/`,
  `exception/`, `security/`, `config/`, `util/`) — see `FOLDER_STRUCTURE.md` for the full
  rationale. Chosen per explicit direction partway through Phase 1; the auth code was
  rebuilt into this shape and re-verified before Jobs was built the same way.
- **`job_skills` table dropped** in favor of a `jsonb` array column directly on `jobs`
  (`must_have_skills` / `nice_to_have_skills`). The skill list per job is small and never
  queried independently of its job, so a normalized join table added complexity without
  benefit.
- **Response DTOs, not RFC7807.** The design doc mentions RFC7807-style errors, but the
  actual frontend contract (confirmed by reading `auth.jsx`) expects
  `{ok:false, message, code}` — that's what's implemented, since goal #1 is "the frontend
  works without frontend changes."
- **Assessment generation is synchronous, not async.** The design doc (and
  Candidates.jsx's own comments) describe `POST /api/assessment/generate` returning
  immediately with deterministic URLs while generation continues in the background, polled
  via `GET /api/assessment/status/{jobId}`. This build generates everything — including
  the Gemini calls — inside the POST itself and returns only once it's fully done. The
  frontend's poll loop still works unmodified: its first poll (after a 3s delay) finds
  `ready:true, generating:false` immediately and stops. Chosen to avoid adding a thread
  pool / job-queue layer for a flow that, in practice, finishes in a few seconds once
  Gemini responds (or fails fast without a key).
- **Invite tokens aren't "coalesced."** Candidates.jsx's comments describe the original
  app returning an *identical* token across repeated mint calls for the same
  email+level, to avoid extra round-trips. That's incompatible with storing only a
  SHA-256 hash (the design doc's explicit requirement) — the raw token doesn't exist
  anywhere to hand back a second time. This build mints a fresh token on every call
  instead. Functionally harmless: the frontend always just uses whatever the latest
  mint response returned, and older unused invites simply expire untouched.
- **`custom`/`mix` sourcing pads via AI on a shortfall; `ai` doesn't touch the bank at
  all.** Not explicit in the design doc, but matches the exact wording of
  Candidates.jsx's own source picker ("Your bank; AI only pads if short" for custom,
  "Half from your bank, half AI" for mix) — see the Assessments section above.
- **`universal_execute` only knows how to run one tool (`PROCESS_RESUMES`).** The
  endpoint is built generically (any `platform_config.available_tools` entry becomes a
  real Gemini function declaration), but there's only ever one call site in the whole
  frontend and it only ever declares that one tool, so that's the only one wired to an
  actual handler. A Gemini call that invokes a different tool name falls back to
  whatever plain text came with it rather than erroring.
- **`Interview.mode` is set by what happens next, not by `prepare`.** Nothing in
  `POST /api/interview/prepare` distinguishes an HR browser-mode session from the first step
  of a phone call — `InterviewRoom.jsx` posts the identical shape for both. So `prepare`
  creates the row as `BROWSER`, a self-service link creates it as `SELF`, and `place_call`
  flips it to `PHONE` at the moment a call is actually placed.
- **Interview `by_token` doesn't consume; `save_by_token` does — by design, not
  oversight.** The design doc's own endpoint table draws this line ("Public. Role,
  question count, language" vs. "Public. Save transcript, **consume** token"), so a page
  refresh mid-interview re-reads the same questions instead of burning the link. Only
  the save step's atomic `UPDATE ... WHERE used_at IS NULL AND expires_at > now()`
  (`InviteRepository.consume`) is the actual single-use guard.

- **Rate limits are in memory, per server.** Fine for this single-node monolith; with several
  instances behind a load balancer each would count separately. Swap `RateLimiter` for a shared
  store (e.g. Redis) if it ever scales out. Bounded memory: expired windows are purged and the
  table is cleared if it ever holds more than 50,000 live addresses.
- **Ownership checks let nonexistent ids through** (see Security) so the services keep giving
  their own 400/404; the guard only refuses "exists AND belongs to someone else".
- **Testcontainers 1.21.4 instead of Spring Boot 3.3.5's managed 1.19.8.** Docker Engine 29
  raised its minimum API version, which the docker-java inside 1.19.x can't speak (`pom.xml`
  overrides `testcontainers.version`).

## Open questions carried over from the design doc

1. ~~Should employees see only their own jobs, or all jobs?~~ **Resolved:** employees see
   only jobs they uploaded; admins see all — matches the frontend's own admin-only
   "owner" filter on the Jobs/Analytics/Employees pages, which only makes sense if
   employees don't already see everyone's jobs.
2. Move candidate stage/notes from the frontend's `localStorage` to the backend now or
   later? *(Currently: later — the `candidates` table built in Phase 3 has no `stage`/
   `notes` columns on purpose, so this is still open.)*
3. Should `POST /api/reset` exist in production?
4. **How do candidates open and submit assessments?** Still true after Phase 7: this
   frontend has no candidate-facing assessment-taking page — `/assessment/JD-0042` and
   the minted `/assessment/JD-0042?token=...` invite links aren't routes that exist
   anywhere in `smartstaff/frontend`. `assessment_questions` and `invites` are fully
   populated by generation/minting, but nothing ever reads an invite by token, marks it
   used, or accepts answers — `AssessmentService.submissions()` stays hard-coded to zero
   for exactly this reason. **Interviews turned out not to share this problem** —
   `CandidateInterview.jsx` at `/interview/:token` is a real, complete page, which is why
   Phase 7 could build `by_token`/`save_by_token` for real. Scorecards still can't mean
   anything until this is resolved for assessments specifically.
5. Should the two frontend bugs found while testing Phase 6 (items #8–9 above) be fixed?
   They're one-line, low-risk changes confined to `Candidates.jsx`, but this build's
   brief was backend-only — flagging rather than fixing.
6. **Phase 7's Gemini question-generation prompt has never seen a real success
   response** — every live test used the same intentionally-invalid key already in
   Settings from earlier phases, so `prepare`/`invites/mint` are only confirmed to (a)
   build a well-formed enough request that Google's own error is about the key, not the
   request shape, and (b) degrade gracefully when Gemini fails. The rest of the pipeline
   (token resolution, atomic consumption, transcript save, transcripts listing, and the
   real frontend UI) was verified by seeding a `PENDING` interview directly in Postgres
   with the exact shape `prepare` would have written, then driving it through the real
   endpoints — see the Interviews section above. A real Gemini key would close this gap
   immediately; until then it's the same category of limitation as Phase 6's AI question
   generation and Phase 5's `PROCESS_RESUMES` tool call.

## Automated tests

`mvn test` — **242 tests, all passing** (72 unit + 170 integration). **Docker must be running**: the
integration tests start a throwaway PostgreSQL 16 container (Testcontainers); nothing touches the
development database.

| Layer | What it is | Classes |
|---|---|---|
| Unit (72) | Plain JUnit, no Spring, milliseconds | `TwilioSignatureValidatorTest` (Twilio's published example), `CryptoServiceTest`, `SkillDictionaryTest`, `ExperienceParserTest`, `QuestionBankFileParserTest`, `FileStorageServiceTest`, `RateLimiterTest` (fake clock + concurrency), `RequestIdFilterTest`, `ProductionSecretsGuardTest`, `JobAccessGuardTest`, `JwtServiceTest` |
| Integration (170) | The whole application: real Spring context, real Flyway migrations, real PostgreSQL, MockMvc | `AuthIntegrationTest`, `JobOwnershipIntegrationTest` (every job-scoped endpoint × intruder/owner/admin), `ScreeningIntegrationTest`, `AssessmentIntegrationTest` (+ invites), `InterviewIntegrationTest` (self-service link lifecycle, 12-way simultaneous submit), `PhoneInterviewIntegrationTest`, `RecruiterChatIntegrationTest`, `SettingsIntegrationTest`, `RateLimitIntegrationTest`, `RateLimitIgnoresForwardedForIntegrationTest`, `ActuatorIntegrationTest` |

**How the external services are covered without real accounts.** `GeminiClient` and `TwilioClient`
take their base URLs from configuration, so the integration tests point them at
`support/StubServer` — a tiny in-JVM HTTP server (JDK's own `com.sun.net.httpserver`, no extra
dependency) that returns Gemini-shaped and Twilio-shaped responses and records every request. That
lets the tests exercise the *success* paths that a placeholder key can never reach: AI question
generation and its parsing, interview prep, the chat's function-calling dispatch into screening,
`place_call`, and the settings "Test" buttons. Twilio's webhooks are driven with requests signed by
`support/Fixtures.twilioSignature`, an independent re-implementation of Twilio's algorithm.

Conventions worth knowing: `IntegrationTestBase` shares one container, one stub and one cached Spring
context across all classes (fast), so tests create their own uniquely-named accounts/jobs/emails and
never assume an empty database — the only global tables, `app_settings` and the question bank, are
cleared before each test. Rate limiting is off in the base configuration (every MockMvc request is
the same "client") and switched on, with tiny limits, only in the rate-limit classes.

**Not covered:** the real Gemini and Twilio services (their actual responses, prompt quality, a real
phone call), the React frontend, and load/performance testing.

## Running it locally

```bash
cd smartstaff/backend-classic
docker compose up -d              # Postgres on localhost:15433 (Docker Desktop must be running)
mvn -DskipTests clean package
java -jar target/backend-classic-0.1.0.jar   # listens on :8000
curl http://localhost:8000/actuator/health   # {"status":"UP",...}
```

Then start `smartstaff/frontend` (`npm run dev`, port 5173) — no `VITE_API_BASE` needed,
it already defaults to `http://localhost:8000` in dev. Demo logins: `admin@viztalent.demo` /
`AdminDemo@123` and `EMP1001` / `EmployeeDemo@123`.

Run the tests with `mvn test` (Docker must be running). To stop the app: stop the `java` process,
then `docker compose stop` for the database. On Windows, stop the running app before rebuilding —
it holds a lock on the jar.

For a production-style start set `SPRING_PROFILES_ACTIVE=prod` plus real values for `JWT_SECRET`,
`APP_ENCRYPTION_KEY` and `POSTGRES_PASSWORD` (see "Operations & configuration"); the app refuses to
start otherwise.
