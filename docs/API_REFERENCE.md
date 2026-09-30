# backend-classic API Reference

Every HTTP endpoint in `backend-classic`, taken directly from the Spring controllers in `src/main/java/com/smartstaff/controller/` and the DTOs in `src/main/java/com/smartstaff/dto/`.

Generated 30 Sep 2026 from source.

## Contents

- [Overview](#overview)
- [Access labels](#access-labels)
- Endpoints: [Auth](#auth) · [Jobs, JDs and resumes](#jobs-jds-and-resumes) · [Screening](#screening) · [Assessment](#assessment) · [Interview](#interview) · [Twilio webhooks](#twilio-webhooks) · [Invites, activity and question bank](#invites-activity-and-question-bank) · [Settings](#settings)
- [Shared response shapes](#shared-response-shapes)

---

## Overview

| | |
|---|---|
| Base URL | `http://localhost:8000` (set by `SERVER_PORT`, default 8000) |
| Endpoints | 51 (50 controller routes plus `/actuator/health`) |
| Auth | `Authorization: Bearer <token>`, with the token from `POST /api/auth/login` |
| JSON style | snake_case |
| Success status | 200 |
| Error body | `{ ok: false, message, code, detail }` |

> **Check before production:** `SecurityConfig.java:87` lets anyone download JDs, resumes and the screening report without signing in (all `GET` on `/api/jd/*/download`, `/api/resumes/**` and `/api/download_report`). Confirm this is intended.

## Access labels

| Label | Meaning |
|---|---|
| **Public** | No token needed |
| **Signed in** | Any valid JWT |
| **Job access** | Caller must own or be allowed on that job or session (the `@jobAccess` guard) |
| **Admin** | Role `ADMIN` |

In the bodies below, `*` marks a required field.

---

## Auth

`AuthController.java`

| Method | Path | Access | What it does |
|---|---|---|---|
| POST | `/api/auth/login` | Public | Sign in and get a JWT |
| POST | `/api/auth/signup` | Public | Create an account |
| POST | `/api/auth/logout` | Signed in | Sign out |
| GET | `/api/auth/me` | Signed in | Return the signed-in account |
| GET | `/api/auth/accounts` | Admin | List admin and user accounts |
| POST | `/api/auth/approve` | Admin | Approve or reject a pending account |

### POST `/api/auth/login`

```
Body:     { role*, identifier*, password* }
Response: { ok, token, account, pending_approval, message, code }
```

### POST `/api/auth/signup`

An admin caller can create pre-approved accounts; other sign-ups may come back pending approval.

```
Body:     { role*, name, email, employee_id, password* }
Response: { ok, token | null, account, pending_approval: bool, message }
```

### POST `/api/auth/logout`

```
Response: { ok: true }
```

### GET `/api/auth/me`

```
Response: { ok, account }
```

### GET `/api/auth/accounts`

```
Response: { ok, admins: [account], users: [account] }
```

### POST `/api/auth/approve`

```
Body:     { employee_id*, approved*: bool }
Response: { ok: true }
```

## Jobs, JDs and resumes

`JobController.java`

| Method | Path | Access | What it does |
|---|---|---|---|
| POST | `/api/upload_jd` | Signed in | Upload a JD file and create a job |
| POST | `/api/upload_jd_skills` | Signed in | Create a job from a typed skills list |
| POST | `/api/upload_resumes` | Job access | Upload one or more resumes to a job |
| GET | `/api/jobs` | Signed in | List jobs visible to the caller |
| GET | `/api/jobs/{id}` | Job access | Full job detail |
| DELETE | `/api/jobs/{id}` | Signed in | Delete a job (ownership checked in the service) |
| GET | `/api/jd/{id}/resumes` | Job access | List resumes uploaded to a job |
| GET | `/api/jd/{id}/download` | Public | Download the original JD file |
| GET | `/api/resumes/{jobId}/download/{fileName}` | Public | Download one resume file |

### POST `/api/upload_jd`

```
Params:   multipart/form-data: file* (JD document), force (optional string)
Response: jdUpload (see Shared response shapes)
```

### POST `/api/upload_jd_skills`

```
Body:     { skills*, title }
Response: jdUpload
```

### POST `/api/upload_resumes`

```
Params:   multipart/form-data: files* (list), session_id* (job UUID)
Response: { status, suggested_reply, has_jd: bool }
```

### GET `/api/jobs`

```
Response: { ok, jobs: [ { job_id, jd_number, jd_number_display, jd_title,
            role_title, jd_filename, skills: [string], skills_count, owner_id,
            owner_name, owner_email, owner_role, created_at, assessment_url,
            interviews_count, submissions_count } ] }
```

### GET `/api/jobs/{id}`

```
Response: { ok, job_id, jd_number_display, jd_title, jd_filename, created_at,
            jd_text,
            jd_struct: { experience_min_years, experience_max_years,
                         preferred_domains, critical_skills, important_skills },
            candidates: [candidateRow], submissions: [..], interviews: [..],
            assessment_urls: { level: url } }
```

### DELETE `/api/jobs/{id}`

```
Response: { ok: true }
```

### GET `/api/jd/{id}/resumes`

```
Response: { ok, resumes: [ { filename, size_bytes, uploaded_at, download_url } ] }
```

### GET `/api/jd/{id}/download` and GET `/api/resumes/{jobId}/download/{fileName}`

```
Response: binary file
```

## Screening

`ScreeningController.java`

| Method | Path | Access | What it does |
|---|---|---|---|
| POST | `/api/run_screening` | Job access | Score every resume on a job against its JD |
| GET | `/api/progress` | Signed in | Poll the current screening progress log |
| POST | `/api/universal_execute` | Job access | Recruiter chat: run a natural-language command against a job |
| GET | `/api/download_report` | Public | Download the screening report as CSV |

### POST `/api/run_screening`

```
Body:     { session_id* }
Response: { reply, table_data: [candidateRow] }
```

### GET `/api/progress`

```
Response: { log: string | null }
```

### POST `/api/universal_execute`

```
Body:     { command*, session_id,
            platform_config: { persona, industry,
              available_tools: [ { tag_name, description, expected_params } ] } }
Response: { reply, table_data: [candidateRow] }
```

### GET `/api/download_report`

```
Response: binary file (CSV)
```

## Assessment

`AssessmentController.java`

| Method | Path | Access | What it does |
|---|---|---|---|
| POST | `/api/assessment/generate` | Job access | Generate the MCQ/coding assessment for a job |
| GET | `/api/assessment/status/{jobId}` | Job access | Assessment readiness and submission counts |
| GET | `/api/assessment/submissions/{jobId}` | Job access | Candidate submissions for a job |
| GET | `/api/assessment/answer_key/{jobId}` | Job access | Answer key grouped by level |

### POST `/api/assessment/generate`

```
Body:     { session_id*, question_source* }
Response: { status, message, assessment_url,
            assessment_urls: { level: url }, counts: { level: int } }
```

### GET `/api/assessment/status/{jobId}`

```
Response: { ok, has_jd, jd_title, num_questions, num_submissions,
            latest_submission_at, assessment_url, ready, generating, error }
```

### GET `/api/assessment/submissions/{jobId}`

```
Response: { ok, submissions: [..], pass_threshold: int (default 50) }
```

### GET `/api/assessment/answer_key/{jobId}`

```
Response: { ok, role_title, levels: [ { level, count, questions: [
            { type, question, options, correct_index, correct_indices,
              skill, difficulty } ] } ] }
```

## Interview

`InterviewController.java`

| Method | Path | Access | What it does |
|---|---|---|---|
| GET | `/api/interview/config` | Signed in | Whether phone interviews are configured |
| POST | `/api/interview/prepare` | Job access | Build interview questions for a candidate |
| POST | `/api/interview/invites/mint` | Job access | Create a candidate interview link |
| POST | `/api/interview/save` | Job access | Save an interview transcript |
| GET | `/api/interview/transcripts/{jobId}` | Job access | All saved transcripts for a job |
| POST | `/api/interview/place_call` | Job access | Start a Twilio phone interview |
| GET | `/api/interview/call_status/{interviewId}` | Job access | Live status and transcript of a phone interview |
| GET | `/api/interview/by_token/{token}` | Public | Candidate opens an interview link |
| POST | `/api/interview/save_by_token/{token}` | Public | Candidate submits answers through the link |

### GET `/api/interview/config`

```
Response: { twilio_configured: bool, from_number }
```

### POST `/api/interview/prepare`

```
Body:     { session_id*, candidate_name, phone, file_name, language }
Response: interviewPrepare (see Shared response shapes)
```

### POST `/api/interview/invites/mint`

```
Body:     { session_id*, candidate_email*, candidate_name, phone, file_name, language }
Response: { status: "success" | "error", message, url }
```

### POST `/api/interview/save`

```
Body:     { session_id*, interview_id*, candidate_name, phone, file_name,
            role_title, transcript*: [turn], started_at, ended_at }
Response: { ok: true }
```

### GET `/api/interview/transcripts/{jobId}`

```
Response: { ok, interviews: [ { interview_id, candidate_name, phone,
            role_title, transcript: [turn], saved_at } ] }
```

### POST `/api/interview/place_call`

```
Body:     { session_id*, interview_id*, phone* }
Response: { status: "ok" | "error", call_sid, detail }
```

### GET `/api/interview/call_status/{interviewId}`

```
Response: { status, transcript: [turn], answered: int, total: int, ended: bool }
```

### GET `/api/interview/by_token/{token}`

```
Response: interviewPrepare
```

### POST `/api/interview/save_by_token/{token}`

```
Body:     { transcript*: [turn], started_at, ended_at }
Response: { ok: true }
```

## Twilio webhooks

`TwilioWebhookController.java`. These are called by Twilio, not by the app. They are open in `SecurityConfig`; the controller checks each request itself.

| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/interview/twiml/voice/{interviewId}` | Twilio form params | `text/xml` (TwiML for the start of the call) |
| POST | `/api/interview/twiml/answer/{interviewId}/{seq}` | Twilio form params | `text/xml` (next TwiML step) |
| POST | `/api/interview/twiml/status` | Twilio form params | empty (call status callback) |

## Invites, activity and question bank

`InviteController.java`, `ActivityController.java`, `QuestionBankController.java`

| Method | Path | Access | What it does |
|---|---|---|---|
| POST | `/api/invites/mint` | Job access | Create assessment invite links per level |
| GET | `/api/activity/{jobId}` | Job access | Activity feed for a job |
| GET | `/api/questions/bank` | Signed in | Question bank stats and uploads |
| POST | `/api/questions/upload` | Admin | Upload a question file to the bank |
| DELETE | `/api/questions/upload/{id}` | Admin | Remove one uploaded question file |
| POST | `/api/questions/clear` | Admin | Clear the whole question bank |

### POST `/api/invites/mint`

```
Body:     { session_id*, candidate_email*, candidate_name, levels*: [string], combined: bool }
Response: { ok, invites: [ { level, url, remaining_seconds } ] }
```

### GET `/api/activity/{jobId}`

```
Response: { ok, activity: [ { kind, message, at } ] }
```

### GET `/api/questions/bank`

```
Params:   query: limit (int, default 1)
Response: { ok, stats: { total, by_type: {..}, by_level: {..}, uploads },
            uploads: [ { id, filename, count, uploaded_at } ] }
```

### POST `/api/questions/upload`

```
Params:   multipart/form-data: file*
Response: { ok, added, total, warnings: [string], message }
```

### DELETE `/api/questions/upload/{id}` and POST `/api/questions/clear`

```
Response: { ok: true }
```

## Settings

`SettingsController.java`. Every settings endpoint requires **Admin**.

| Method | Path | What it does |
|---|---|---|
| GET | `/api/config` | Current platform configuration |
| POST | `/api/config/gemini` | Save the Gemini API key |
| POST | `/api/config/gemini/test` | Test the Gemini key |
| POST | `/api/config/public_url` | Set the public base URL used in candidate links |
| POST | `/api/config/invite_ttl` | Set invite link lifetime |
| POST | `/api/config/twilio` | Save Twilio credentials |
| POST | `/api/config/twilio/test` | Test the Twilio credentials |
| POST | `/api/config/piston_url` | Set the Piston code-runner URL |
| POST | `/api/reset` | Reset platform data |

Also: `GET /actuator/health` is **Public** and returns `{ status }`.

### GET `/api/config`

```
Response: { ok,
            gemini:      { configured, has_key, model, key_preview },
            public_url:  { value, is_localhost, assessment_base },
            twilio:      { configured, account_sid_preview, auth_token_preview, from_number },
            code_runner: { piston_url, piston_enabled, languages },
            invites:     { ttl_seconds, ttl_hours } }
```

### POST `/api/config/gemini`

```
Body:     { api_key, persist: bool }
Response: { status, message }
```

### POST `/api/config/gemini/test`

```
Response: { ok, message, model, sample }
```

### POST `/api/config/public_url`

```
Body:     { public_base_url*, persist: bool }
Response: { public_base_url, assessment_base, is_localhost }
```

### POST `/api/config/invite_ttl`

```
Body:     { ttl_seconds*: >= 60, persist: bool }
Response: { ttl_seconds, ttl_hours }
```

### POST `/api/config/twilio`

```
Body:     { account_sid, auth_token, from_number, persist: bool }
Response: { twilio_configured: bool }
```

### POST `/api/config/twilio/test`

```
Response: { ok, message, account_name, from_number }
```

### POST `/api/config/piston_url`

```
Body:     { piston_url, persist: bool }
Response: { piston_url, piston_enabled, languages }
```

### POST `/api/reset`

```
Response: { ok: true }
```

---

## Shared response shapes

```
account          = { id, role, name, email, employee_id, approved: bool,
                     last_seen_at, last_login_at, created_at }

jdUpload         = { status, suggested_reply, job_id, jd_title, jd_number_display,
                     assessment_url, evicted_jobs: [ { job_id, jd_number, jd_title } ],
                     existing_job_id, existing_jd_title, existing_jd_number_display,
                     has_jd: bool }

candidateRow     = { File_Name, Candidate_Name, Email, Phone, Years_Experience: int,
                     Fit_Score_Out_Of_100: int, Matched_Count: int, Total_Required: int,
                     Key_Strengths, Missing_Skills, Score_Breakdown, Job_Id }

interviewPrepare = { interview_id, role_title, candidate_name, language, intro, outro,
                     questions: [ { category, skill, question } ] }

turn             = { category, skill, question*, answer }

error            = { ok: false, message, code, detail }
```
