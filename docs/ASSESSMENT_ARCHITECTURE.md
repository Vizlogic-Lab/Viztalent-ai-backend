# Assessment process — architecture review and target design

Date: 2026-10-06 · Scope: `backend-classic` + `frontend/Viztalent-ai-frontend`, the online-assessment flow only (Twilio phone interviews are out of scope for now).

---

## 1. The flow today

```
JD upload ──► Role profile (Gemini, async)
   │
   ▼
Resume screening (rule-based, no AI) ──► shortlist
   │
   ▼
Generate assessment (async job)            ◄── Gemini: 1 call per slot, up to 3 tries per slot
   │  QUEUED → GENERATING → VALIDATING → READY / FAILED
   ▼
SME review (ReviewRouter) ──► approved
   │
   ▼
Invite (HTTP request from the Email Preview)
   │  screened candidate with a resume?
   │     yes → PersonalAssessmentGenerator.readyFor()  ◄── Gemini, INSIDE the request
   │     no  → job's shared READY assessment
   ▼
Candidate attempt (proctored, timed) ──► Run code (Piston)
   │
   ▼
Submit ──► EvaluationJob (async)          ◄── Gemini rubric grading for written answers
   │        objective scoring + hidden tests + rubric, retry scheduler
   ▼
Scorecard / Results
```

The building blocks are sound: async jobs, a `GeminiClient` with retry/backoff and a fallback model, a daily cap with "critical" features, deterministic scoring rules, a retry scheduler for evaluation. The problems are in **where AI sits in the flow** and **how many calls each step makes**.

---

## 2. What is wrong (with evidence from the local database, 2026-10-06)

| # | Problem | Evidence | Impact |
|---|---|---|---|
| P1 | **AI is on a synchronous user path.** Minting an invite generates the candidate's personal assessment with Gemini inside the HTTP request. | Email Preview error: *"Could not mint per-candidate links … Gemini 429"*. | Recruiter can't send an invite whenever Gemini is slow or out of quota. *(Partly fixed today: the invite now falls back to the job's shared assessment — see `InviteServiceImpl`.)* |
| P2 | **The frontend mints 3 times in parallel** (L1, L2, L3) for every preview, and each call can trigger personal-assessment generation. | `Candidates.jsx` lines ~198–203. | Up to 3× the Gemini calls for one candidate, and a race that can create duplicate personal assessments. |
| P3 | **Question generation is call-heavy.** One Gemini call per slot, up to 3 validation retries per slot, and each call itself retries 429s 3× and then tries the fallback model. | `ai_usage_daily` today: `QUESTION_GENERATION 10 calls / 10 failures`. | A single assessment bursts dozens of calls; on the free tier this hits the per-minute limit almost immediately. |
| P4 | **Generation mostly fails.** | `assessments`: **27 FAILED vs 4 READY**, all with *"No question could be generated or found in the question bank."* | Jobs have no usable assessment, which also defeats the P1 fallback. |
| P5 | **The question bank can't act as a safety net.** | `question_bank_items`: **5 questions** total (2 MCQ L1, 1 MSQ L1, 1 CODE_WRITE L2, 1 SCENARIO L2). | When AI fails, there is nothing to fall back to. |
| P6 | **The code runner is off.** | `app_settings.piston_enabled = false`. | Coding slots are skipped or can't be validated with hidden tests; coding answers can't be auto-graded. |
| P7 | **One model for every feature.** | `gemini_model = gemini-3.8-flash`, fallback `gemini-3.7-flash`, used by chat, role profile, generation, grading alike. | Can't put the paid "Pro" model only where quality matters (generation, grading) and keep cheap/fast work on Flash. |
| P8 | **No global rate limiter in front of Gemini.** Retries happen per call, but nothing spaces calls across the whole app. | `GeminiClient` retries per request; executors run 2–4 jobs in parallel. | Parallel jobs amplify 429s instead of queueing politely. |

**Root cause in one line:** the design assumes Gemini is always available and cheap, so AI sits on the critical path and is called per item; on a free-tier key it is neither.

---

## 3. Target architecture

### 3.1 Principles

1. **No AI call inside a user-facing request.** Recruiter clicks and every candidate action only read and write the database. AI runs only in background jobs.
2. **Every AI step has a deterministic fallback** (question bank, rule-based scoring, "awaiting human review").
3. **Prepare ahead of time.** Assessments (shared and personal) are generated when a candidate is shortlisted, not when the invite is sent.
4. **Fewer, bigger AI calls.** One call per section or level, not one per question.
5. **One AI gateway** owns rate limits, priorities, model routing, caching and cost.

### 3.2 Components

```
                    ┌───────────────────────────────────────────────┐
                    │                 AI Gateway                    │
 background jobs ──►│ priority queue: GRADING > PERSONAL > GENERATION│──► Gemini
 only               │   > ROLE_PROFILE > SUMMARY > CHAT               │   (Flash / Pro
                    │ token bucket per model (RPM, TPM, RPD)          │    per feature)
                    │ model routing per feature                       │
                    │ response cache by prompt hash · daily budget    │
                    └───────────────────────────────────────────────┘

JD ─► Role profile job ─► Assessment generation job ─► Validator ─► SME review ─► READY
                                 │ (per section, batched)   │ (rules + Piston)        │
                                 └──── question bank ◄──────┘◄── approved questions harvested
Shortlist ─► Personal assessment job (background, optional) ─► READY or "use shared"
Invite    ─► DB only: pick personal READY ?: shared READY ─► token + email
Attempt   ─► DB only (+ Piston for "Run code")
Submit    ─► Evaluation job: objective + hidden tests first, AI rubric second ─► Scorecard
```

### 3.3 Stage by stage

| Stage | Today | Target |
|---|---|---|
| Role profile | Gemini, async | Keep. Route to Flash. Rule-based skills from the JD as the fallback (already parsed at upload). |
| Assessment generation | 1 call per slot, ~18 slots | **1 call per level/section** returning all questions for it under a JSON schema; validate each; regenerate only the rejected ones in one follow-up call. Route to **Pro** once billing is on. |
| Validation | Rules + (Piston, currently off) | Same, with Piston **on** so code questions get their reference solution and hidden tests verified. |
| Question bank | 5 items, manual upload | **Harvest** every SME-approved question into the bank automatically, tagged by skill/level/type. The bank grows with use and becomes the real fallback. Seed it with ~30–50 questions per main role before go-live. |
| Personal assessment | Generated on invite, in the request | Generated by a **background job on shortlist** (or by a "prepare" button). Invite picks it only if READY, else the shared one. |
| Invite | Can call Gemini | **DB only.** One mint request for all chosen levels (frontend change). Idempotent per candidate+level while an unused invite is still valid. |
| Attempt | DB + Piston | Unchanged. |
| Evaluation | Objective + tests + Gemini rubric, async with retry | Unchanged design. Route rubric grading to **Pro**; GRADING keeps highest gateway priority; if AI is down, the attempt waits in `AWAITING_REVIEW` (already supported) rather than failing. |
| Results | Scorecard | Unchanged. AI summary is optional and lowest priority. |

### 3.4 Model routing (config, not code)

| Feature | Model now (free key) | Model after billing |
|---|---|---|
| QUESTION_GENERATION | Flash | **Pro** |
| PERSONAL_ASSESSMENT | Flash | **Pro** |
| GRADING | Flash | **Pro** (consistency matters: it already grades twice and compares) |
| ROLE_PROFILE, SUMMARY, RECRUITER_CHAT | Flash | Flash |

Store this as a setting (`ai_model_by_feature`), editable in Settings → AI provider like the current model, so moving to Gemini Pro needs no code change and no redeploy.

---

## 4. Plan (priority order)

| Phase | Work | Fixes |
|---|---|---|
| **0 — done today** | Invite falls back to the shared assessment when the personal one can't be generated. | P1 (partly) |
| **1 — make it reliable on the free key** | (a) Frontend: one mint call for the chosen levels instead of three parallel calls. (b) Move personal-assessment generation to a background job triggered on shortlist; invite becomes DB-only. (c) Global rate limiter in `GeminiClient` (token bucket sized to the key's RPM, configurable). (d) Seed the question bank (≥ 6 per type/level for your main roles). | P1, P2, P5, P8 |
| **2 — cut AI calls 10×** | Batch generation per level/section; regenerate only rejected questions; cache identical prompts. Auto-harvest approved questions into the bank. | P3, P4, P5 |
| **3 — code questions** | Enable Piston (needs your approval to start its container) with the languages you test (Python, Java, C, C++). | P6 |
| **4 — Gemini Pro with billing** | Add per-feature model routing; set Pro for generation/personal/grading; set a daily **budget** (calls + tokens) per feature; alert when 80% is used. Run the calibration set (`CalibrationRunJob`) on Flash vs Pro to compare grading quality before switching. | P7 |
| **5 — production hardening** | Usage/cost dashboard from `ai_usage_daily`; alert on FAILED generation rate; load test 50 concurrent candidates (attempt + run code + submit). | — |

### Acceptance checks per phase

- **Phase 1:** with the Gemini key removed, a recruiter can still send an invite for any job that has a READY shared assessment, and a candidate can complete and submit it; written answers go to `AWAITING_REVIEW`.
- **Phase 2:** generating one 18-question assessment makes ≤ 6 Gemini calls (was ~18–54+), and FAILED generations drop to near zero on the free key.
- **Phase 4:** switching Flash ↔ Pro for a feature is a Settings change only; the calibration run shows grading agreement at or above the Flash baseline.

---

## 5. Testing now vs. after billing

- **Now (free Gemini key):** expect 429s. Test the *fallbacks* deliberately: generate with a seeded bank, invite without AI, submit and see `AWAITING_REVIEW`. Keep the daily cap low.
- **After billing (Gemini Pro):** raise the gateway's RPM to the paid quota, turn on per-feature routing, set a budget, then re-run generation and calibration to measure quality and cost per assessment.
