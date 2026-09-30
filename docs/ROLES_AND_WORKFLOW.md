# Viztalent AI — Roles and the flow from Admin to Candidate

Last checked: **2026-09-24** · Covers the backend `smartstaff/backend-classic` together with the web app `smartstaff/frontend`.
Related: [FEATURE_STATUS.md](FEATURE_STATUS.md) (what is complete and what is not) · [FEATURES.md](FEATURES.md) (technical details).

---

## 1. The roles

| Role | Who they are | How they get in |
|---|---|---|
| **Admin** | Recruiter or hiring manager. Runs the system. | Email + password |
| **Employee** | HR staff. Does the daily recruiting, only on their own jobs. | Employee ID + password. An admin must approve the account first. |
| **Candidate** | The job applicant. | **No account.** Uses a single-use link that HR sends. |
| **Visitor** | Anyone who is not logged in. | Nothing |
| **Twilio** | The phone service. A system, not a person. | Signed requests only |

### Admin — can do everything
- **Accounts:** see every account with an online/offline dot; approve or reject employees; create employees directly (they skip the approval queue).
- **Jobs and resumes:** see **all** jobs (with an "uploaded by" column and an owner filter); upload job descriptions and resumes; delete any job; download files.
- **Screening:** run it on any job; use the AI assistant; download the report.
- **Assessments:** build tests (question bank, AI, or a mix); view the answer key; create invite links for any job.
- **Interviews:** send AI interview links, run interviews in the app, place phone calls, read any transcript.
- **Settings (admin only):** Gemini key, Twilio details, public URL, link expiry time, code-runner address.
- **Question bank (admin only):** upload CSV / JSON / Excel files, delete an upload, clear the bank.

### Employee — the same daily work, but only on their own jobs
- **Can:** upload JDs and resumes; screen and rank; use the AI assistant; build assessments and invite links; send interview links, run browser and phone interviews; read transcripts — **only for jobs they uploaded**. They can delete their own jobs.
- **Cannot:** open Settings; open the Employees page; approve anyone; change the question bank; see or touch anyone else's jobs (the backend answers 403 "no permission").
- **Account states:** waiting for approval → cannot log in. Rejected or removed by an admin → locked out immediately, even if they were already logged in.

### Candidate — the least power, and no account
- **Interview link (works today):** opens the interview page with no login; tests the microphone; hears each question read aloud; answers by speaking; can repeat a question, go to the next one, mute the interviewer, or end early; submits **once**. The link then stops working. It also expires (48 hours by default).
- **Phone interview (needs Twilio set up):** gets a call, answers by voice, needs no app.
- **Assessment link:** the backend creates it, but **no test page exists yet**, so a candidate cannot take an assessment today.
- **Cannot:** see their score or results, see other candidates, open any HR screen, or retry after submitting.

### Visitor (not logged in)
Can open the login and sign-up pages, log in, sign up (an employee sign-up waits for approval), and use direct download links. Every other part of the API answers 401 "login required".

### Side by side

| Capability | Admin | Employee | Candidate |
|---|:-:|:-:|:-:|
| See all jobs | ✅ | own only | ❌ |
| Upload JD and resumes, screen, AI assistant | ✅ | own jobs | ❌ |
| Build assessments, create invite links | ✅ | own jobs | ❌ |
| Interviews and transcripts (link, in-app, phone) | ✅ | own jobs | ❌ |
| Approve employees | ✅ | ❌ | ❌ |
| Settings and question bank | ✅ | ❌ | ❌ |
| Take the interview (link or phone) | n/a | n/a | ✅ once |
| Take an assessment | n/a | n/a | ❌ not built |
| See scores and results | ✅ | own jobs | ❌ |

---

## 2. The flow at a glance

```text
ADMIN (one-time setup)
  1. Log in
  2. Settings: Gemini key, public URL, link expiry, Twilio (optional), question bank (optional)
  3. Employees page: approve staff who signed up
                |
                v
HR (admin, or an approved employee on their own jobs)
  4. Upload the job description (or paste a skills list)
        -> system reads the skills and the years of experience, gives it a number (JD-0001)
  5. Upload the resumes
  6. Screen (button, or ask the AI assistant)
        -> system scores and ranks every resume, no AI involved
  7. Candidates page
        score 50 or more -> "Preview Invite" or "Interview"
        score under 50   -> "Preview Rejection"
  8. System creates a single-use link that expires
  9. HR clicks "Open in mail client" -> their own mail app opens -> HR presses Send
                |
                v
CANDIDATE (no account)
 10. Opens the email and clicks the link
        A. Interview link  -> tests the microphone -> hears each question -> speaks the answers -> submits once
        B. Phone interview -> the phone rings -> an AI voice asks the questions -> answers by speaking
        C. Assessment link -> not built yet (no test page)
                |
                v
HR again
 11. The interview transcript appears on the Candidates page
 12. HR reads the answers and decides. Dashboard and report update
```

---

## 3. Step by step

### A. One-time setup (Admin)

| # | Who | What they do | What the system does |
|---|---|---|---|
| 1 | Admin | Logs in with email + password | Checks the password, gives a login token (valid 24 hours). Repeated wrong tries are slowed down (10 per minute per computer). |
| 2 | Admin | **Settings** page | **Gemini key:** stored encrypted, shown only as `AIza…7890`; a Test button checks it with Google. **Public URL:** the address candidates will open; every link starts with it. **Link expiry:** how long links last (default 48 hours). **Twilio** (only for phone interviews): account SID, token, "from" number; a Test button checks them. **Question bank** (optional): upload CSV / JSON / Excel questions. |
| 3 | Admin | **Employees** page | Shows staff who signed up. Approve or reject them, or create accounts directly. A rejected or removed employee is locked out at once. |

### B. Create the job (HR)

| # | Who | What they do | What the system does |
|---|---|---|---|
| 4 | HR | Uploads the job description (PDF, Word or text), or pastes a list of skills | Extracts the text, finds the **required** and **nice-to-have** skills and the years of experience, names the job from the file name, gives it a number (JD-0001, JD-0002…), and warns if the same JD was uploaded before. Only the newest 100 jobs are kept. |
| 5 | HR | Uploads many resumes for that job | Stores each file and reads its text. A damaged file doesn't stop the others; duplicate file names are renamed. |

### C. Screen the resumes (HR)

| # | Who | What they do | What the system does |
|---|---|---|---|
| 6 | HR | Clicks screen, **or** types or says "screen the resumes" to the AI assistant | For every resume: reads the name, email, phone and years of experience; checks the skills; scores it (required skills 85 points, nice-to-have 15, experience ±5). No AI is used for scoring, so the same resume always gets the same score. Ranks the candidates; **50 or more = shortlisted**. Can be run again after adding resumes. |

### D. Decide and contact (HR)

| # | Who | What they do | What the system does |
|---|---|---|---|
| 7 | HR | Opens the **Candidates** page | Shows the ranked table. For a shortlisted candidate: **Preview Invite** (assessment) or **Interview**. Below 50: **Preview Rejection**. For an assessment the level is suggested from experience: 0–3 years → L1, 4–7 → L2, 8+ → L3. If no test exists yet, HR first chooses how to build it: question bank, AI, or a mix. |
| 8 | HR | Confirms the invite | Creates a **single-use link** (a random token; only a scrambled copy is stored) that expires. For an interview link, Gemini first writes 5 questions from the job description and the candidate's resume, in the chosen language. Phone interviews need Twilio. |
| 9 | HR | Reviews and edits the message, then clicks **Open in mail client** (or **Copy**) | Builds the message and the link. **The system does not send the email** — see section 5. HR presses Send in their own mail app. |

### E. The candidate

| # | Who | What they do | What the system does |
|---|---|---|---|
| 10A | Candidate | **Interview link:** opens it in Chrome or Edge, allows the microphone, presses Start | The page reads each question aloud, listens, and shows what it heard. The candidate uses **Next question**, **Repeat question**, mute, or **End interview**. Speaking and listening happen **in the candidate's browser**. When finished, the answers are saved and the link stops working. A refresh in the middle does not use up the link. |
| 10B | Candidate | **Phone interview:** answers the call | Twilio calls the candidate. An AI voice reads the intro and each question; the spoken answer is saved as it arrives; after the last question the call says goodbye and hangs up. |
| 10C | Candidate | **Assessment link** | **Not built yet** — there is no page for taking the test. |

### F. Back to HR

| # | Who | What they do | What the system does |
|---|---|---|---|
| 11 | HR | Opens the **Candidates** page | The interview appears under **L1 Interview Transcripts** with the candidate's name, how many questions were answered, and the full questions and answers. |
| 12 | HR | Decides the next step | The Dashboard and Analytics update with the screening numbers (the assessment numbers stay at zero — see section 7). The candidate report can be downloaded. |

---

## 4. Other paths

- **A new employee joins:** on the sign-up page they choose *Employee* and enter an Employee ID, name and password → the account is *waiting for approval* → an admin approves it on the Employees page → the employee logs in and follows steps 4–12 for their own jobs.
- **Using the AI assistant instead of buttons:** on the Resume Screening page HR types or speaks to the assistant. The assistant (Gemini) can decide to run the screening and returns the ranked table. If Gemini is unavailable or no key is saved, the assistant answers with a friendly message instead of an error.
- **Rejection:** for a score under 50, **Preview Rejection** gives a polite message with no link. HR sends it the same way as any other email.
- **Assessment (partly built):** HR can build the test, see the answer key and create links per level or one combined link. The candidate side (taking the test, marking, results, scorecard PDF) does not exist yet.
- **Phone interview:** HR chooses *Interview → phone*, confirms the number, and the system prepares the questions and asks Twilio to dial. A live monitor shows the call status and the answers as they arrive.
- **Link problems:** an expired link shows the candidate "This interview link has expired."; a used one shows "This interview has already been completed."; anything else shows "This interview link is invalid." HR simply creates a new link (each creation gives a fresh one).

---

## 5. How the email step works

- **The backend sends no email at all.** There are no mail server settings and no mail code in it.
- What Viztalent AI does is **prepare** the message: recipient (the email found in the resume), subject, and a body with the link. HR can edit all three.
- Two buttons finish the job: **Open in mail client** (opens the recruiter's own mail program with the message already filled in, using a standard `mailto:` link) and **Copy** (copies the text so it can be pasted anywhere).
- HR presses **Send** in their own mail app. Because of this, Viztalent AI cannot tell whether an email was sent, opened or bounced.
- A candidate with no email in their resume cannot be mailed from the app; HR has to add contact details by hand.
- Sending automatically would need an email provider connected to the backend. That is not built.

---

## 6. Before real candidates can use the links (setup checklist)

Everything above works on one computer. For a candidate on another device or network, these must be true (based on how the code works; not yet tried with a real tunnel):

1. **The web app is reachable from the internet** (a deployed site, or a tunnel such as ngrok or Cloudflare).
2. **The backend is reachable too.** In development the web app calls `http://<same host>:8000`. When it is served from a public address it calls that **same address** for `/api/...` unless it was built with `VITE_API_BASE` set to the backend's address. So either put both behind one domain (one proxy that sends `/api/*` to the backend and everything else to the web app), or build the web app with `VITE_API_BASE`.
3. **Allow the web app's address on the backend:** set `CORS_ORIGINS` (the default only allows `http://localhost:5173` and `:4173`).
4. **Settings → Public URL** = the web app's public address. Interview links are `<public URL>/interview/<token>`. For **phone** interviews the same base address must also reach the backend at `/api/interview/twiml/...`, because Twilio calls back there — so the one-domain setup in point 2 is the simplest.
5. **A real Gemini key** (creating an AI interview link needs it) and, for phone calls, a **Twilio account**. A Twilio trial account can only call numbers you have verified.
6. For a production start use `SPRING_PROFILES_ACTIVE=prod` with real secrets (the app refuses to start with the development defaults — see FEATURES.md).

---

## 7. Not working yet

- **Assessments for candidates:** no test page, no saving of answers, no marking, no results table, no scorecard PDF, and no "Find any missing submission" (features 7.7 – 7.12 in FEATURE_STATUS.md).
- **AI and phone features have never run against real accounts.** The Gemini key and Twilio details saved in Settings are test values. They are tested against stand-in servers that imitate Gemini and Twilio.
- **HR's notes and pipeline stages** are kept in the HR person's browser, not on the server.
- **Emails are not sent by the system** (section 5).
- Two small bugs in the frontend `Candidates.jsx` (the Answer Key popup shows empty; the job switcher can use an old job) are documented but not fixed.

---

## 8. Security notes (known gaps, not fixed yet)

1. **Anyone can create an admin account.** The sign-up form lets a visitor choose *Admin*; the account is approved immediately and logged in. The original app worked this way. A safer rule: only an existing admin may create admin accounts (the frontend's *Admin* sign-up would then show an error).
2. **The candidate report download is public.** `/api/download_report` works without logging in and returns the whole candidate list, because the frontend opens it as a plain download link that cannot carry a login. A proper fix needs a small frontend change (download with the login token).

Everything else in the flow is protected: employees are limited to their own jobs, secrets are stored encrypted, links are single-use with only a hash stored, repeated login and link guessing is slowed down, and phone webhooks must carry Twilio's signature.
