# Interview Channels — Design Spec

Design for the two candidate interview channels, to be reviewed **before** implementation.

- **Video interview** → add a **real camera** (record video answers, store & play back).
- **Phone interview** → get it working **end-to-end** with a real Twilio account + public tunnel.

Chosen approach: **design both, then build.** This document is the design.

---

## 1. Where we are today

| Piece | Status today |
|-------|--------------|
| `interviews` table | ✅ has `mode (SELF/BROWSER/PHONE)`, `status`, `language`, `phone`, `intro`, `outro`, `twilio_call_sid`, `twilio_call_status` |
| `interview_turns` table | ✅ `seq`, `category`, `skill`, `question`, `answer` (text only — **no media column**) |
| Browser interview | ✅ questions from Gemini; answers captured as **text via Web Speech API** (mic → transcript). **No camera, no recording.** |
| Phone interview | ✅ full code path (`PhoneInterviewService`, `TwilioClient`, TwiML webhooks, `X-Twilio-Signature` validation). ❌ never run against a **real** Twilio account / real call. |
| Public URL | ⚠️ Settings holds a **dead placeholder** (`abc123.ngrok-free.app`). Webhooks & assessment links need a **live** tunnel. |

---

## 2. Video interview — add real camera

### 2.1 What changes
Today the browser interview only records **text** (speech-to-text). We add **camera video recording** of each answer, uploaded to the backend and viewable by the recruiter. Speech-to-text stays (for scoring/search); the video is the evidence.

### 2.2 Frontend (React — `CandidateInterview.jsx`)
1. On start: `navigator.mediaDevices.getUserMedia({ video: true, audio: true })` → show a **live camera preview** + a mic permission gate.
2. Per question: start a `MediaRecorder` (`video/webm; codecs=vp8,opus`) when the question is read; stop when the candidate finishes → produces one `Blob` per answer.
3. Keep the existing Web Speech transcript in parallel (so `answer` text is still filled).
4. Upload each answer's video blob to the backend **as it completes** (don't hold the whole session in memory).
5. Show upload progress + a retry on failure; block "Next" until the current answer's upload succeeds.

### 2.3 Backend
- **New endpoint:** `POST /api/interview/{interviewId}/turn/{seq}/video` (multipart, `video/webm`).
  - Guarded by the interview token (candidate side) — reuse the single-use/interview-token model, not a JWT.
  - Size limit (e.g. **25 MB/answer**, configurable), content-type allow-list (`video/webm`, `video/mp4`).
  - Store under `storage/interviews/{interviewId}/{seq}.webm` (same storage root as résumés).
- **Recruiter playback:** `GET /api/interview/{interviewId}/turn/{seq}/video` (JWT + `@jobAccess`) streams the file back with range support.

### 2.4 Data model (new migration `V9__interview_media.sql`)
```sql
ALTER TABLE interview_turns
  ADD COLUMN video_path        VARCHAR(512),
  ADD COLUMN video_bytes       BIGINT,
  ADD COLUMN video_uploaded_at TIMESTAMPTZ;
```
(One video per turn. A separate `interview_media` table is overkill for one file per answer.)

### 2.5 Recruiter review UI
- On the candidate's interview review screen: per question show the **question, the transcript, and an inline `<video>` player** for the recorded answer.

### 2.6 Risks / decisions
- **Storage growth:** ~2–8 MB/answer × 5 answers ≈ 10–40 MB/candidate. Fine locally; needs object storage (S3/GCS) for production — out of scope now, note it.
- **Browser support:** `MediaRecorder` webm works in Chrome/Edge/Firefox; **Safari/iOS** needs `video/mp4` fallback — handle later, target Chrome first.
- **No transcode:** store raw webm; the recruiter's browser plays it. (Transcoding = future.)

---

## 3. Phone interview — end-to-end (real Twilio)

The code is done; this is about **wiring real telephony**. Nothing new to build unless a gap shows up during a live call.

### 3.1 Call flow (already implemented)
```
Recruiter clicks "Call candidate"
  → backend POST Twilio /Calls.json (from Twilio number → candidate phone)
  → Twilio dials candidate; on answer requests our TwiML:
      /api/interview/twiml/voice   → <Say> intro, then <Gather input="speech"> Q0
      /api/interview/twiml/answer  → store answer, <Gather> next Q  (repeat)
      → after last Q: <Say> outro, <Hangup>
  → /api/interview/twiml/status → mark COMPLETED / FAILED
Every webhook is verified with X-Twilio-Signature over (public URL + sorted params).
```

### 3.2 What's needed to run it (inputs from you)
1. **Twilio account** — Account SID, Auth Token, and a Twilio **phone number**. Entered in Settings (stored AES-256-GCM encrypted).
2. **Live public tunnel** to the backend (:8000), e.g. `ngrok http 8000`, set as **"Public backend URL"** in Settings. Twilio must reach the webhooks, and signature validation is computed against **exactly this URL**.
3. **Candidate phone number** on the candidate record (E.164, e.g. `+9198…`).

### 3.3 Twilio trial-account limits (important)
- Trial can **only call verified numbers** → verify the candidate's number in the Twilio console first.
- Trial plays a **"trial account" message** before the interview audio.
- Trial voice is limited to your **sign-up country** and has ~limited free minutes.
- → For a real demo to any candidate, the account must be **upgraded** (paid).

### 3.4 Cost (per earlier analysis)
~₹34 / candidate for a 5-min call (call minutes + speech recognition), + ~₹96/mo number rental.

---

## 4. Two different "public URL" needs (don't confuse them)

| Link | Must reach | Who opens it |
|------|-----------|--------------|
| **Twilio webhooks** | the **backend** (:8000) | Twilio's servers |
| **Assessment / video interview link** | the **frontend** (:5173), which then calls the backend | the **candidate** |

Locally same-machine: assessment/video link base = `http://localhost:5173`. For real candidates, both the frontend and `/api` must be reachable under one public origin (production model: nginx serves the SPA and proxies `/api` → :8000). We'll resolve this when we go past local testing.

---

## 5. Build order (proposed)

**Milestone A — Video interview (build first, no external accounts needed)**
1. `V9__interview_media.sql` migration.
2. Backend upload + playback endpoints + storage + size/type limits.
3. Frontend: camera preview, per-answer `MediaRecorder`, upload with progress.
4. Recruiter review: inline `<video>` per answer.
5. Verify end-to-end locally in the browser.

**Milestone B — Phone interview live**
1. You provide Twilio SID/token/number + start a tunnel; enter both in Settings.
2. Add candidate phone number.
3. Place one real test call; walk the TwiML flow; confirm answers stored + status COMPLETED.
4. Fix any gap the live call reveals.

---

## 6. What I need from you to build

- **For video (Milestone A):** nothing external — I can start now. One decision: **per-answer recording** (recommended) vs one long recording for the whole session.
- **For phone (Milestone B):** Twilio **Account SID + Auth Token + phone number**, a **live tunnel URL**, and a **verified candidate phone number**. (Paste credentials only when ready; I'll enter them via Settings, never commit them.)
