# Viztalent AI backend — Feature status: is it complete?

Last checked: **2026-09-24** · Covers `smartstaff/backend-classic` (the backend that serves `smartstaff/frontend`).
For technical details of each feature see [FEATURES.md](FEATURES.md); for the code layout see [FOLDER_STRUCTURE.md](FOLDER_STRUCTURE.md); for who can do what and the step-by-step flow from admin to candidate see [ROLES_AND_WORKFLOW.md](ROLES_AND_WORKFLOW.md).

---

## The short answer

**Not 100% complete — but everything the current app screens use works, except one area.**

| | |
|---|---|
| ✅ **Works** | Login and accounts, jobs and resumes, resume screening, settings, question bank, assessment *creation*, AI interview links, interview transcripts, security and monitoring. |
| ❌ **Missing** | The *candidate side of online assessments*: taking the test, saving answers, automatic marking, results, scorecard PDF, and the "Find any missing submission" button. (The frontend has no page for a candidate to take a test, so this needs both a new frontend page and new backend endpoints.) **Also open: two security gaps** — anyone can sign up as an admin, and the candidate report download link works without a login (10.11 and 10.12). |
| 🔑 **Built, but never tried with real accounts** | Everything that uses Google Gemini (AI questions, AI interview scripts, the AI chat assistant) and real Twilio phone calls. The Gemini key and Twilio details saved in Settings are test values, so the "success" case has never been seen. It is checked up to the point where Google/Twilio answer, and against a stand-in server that imitates them. |

Of the ~50 backend endpoints the frontend calls, **all but two exist**. The two that don't:
`GET /api/assessment/submissions_all` and `GET /api/scorecard/{jobId}/{idx}` — both belong to the missing assessment-results area.

### Legend

| Mark | Meaning |
|---|---|
| ✅ | **Complete** — built and checked. |
| 🟡 | **Partly done** — works, with a limitation written in the notes. |
| 🔑 | **Built, not proven live** — needs a real Gemini key and/or a real Twilio account to see the real result. |
| ❌ | **Not built.** |

### Scoreboard (69 features)

| Area | ✅ | 🟡 | 🔑 | ❌ | Total |
|---|:-:|:-:|:-:|:-:|:-:|
| 1. Accounts and login | 7 | 0 | 0 | 1 | 8 |
| 2. Jobs and resumes | 8 | 0 | 0 | 0 | 8 |
| 3. Resume screening | 4 | 2 | 0 | 0 | 6 |
| 4. AI recruiter assistant | 1 | 0 | 1 | 0 | 2 |
| 5. Settings | 4 | 2 | 0 | 0 | 6 |
| 6. Question bank | 3 | 0 | 0 | 0 | 3 |
| 7. Online assessments | 3 | 1 | 2 | 6 | 12 |
| 8. AI interviews (link, browser, phone) | 5 | 0 | 5 | 0 | 10 |
| 9. Dashboard and activity | 1 | 1 | 0 | 0 | 2 |
| 10. Security and reliability | 9 | 0 | 0 | 3 | 12 |
| **All features** | **45** | **6** | **8** | **10** | **69** |

---

## 1. Accounts and login

| # | Feature | Status | Notes |
|---|---|:-:|---|
| 1.1 | Admin logs in with email + password | ✅ | Demo: `admin@viztalent.demo` / `AdminDemo@123` |
| 1.2 | Employee logs in with Employee ID + password | ✅ | Demo: `EMP1001` / `EmployeeDemo@123` |
| 1.3 | Sign-up | ✅ | An employee who signs up waits for approval; an admin creating an employee approves them straight away. |
| 1.4 | Admin approves or rejects employees | ✅ | Employees page. |
| 1.5 | Employees page: account list with online/offline dot | ✅ | Based on "last seen". |
| 1.6 | A removed / un-approved employee is locked out immediately | ✅ | Added in Phase 9. Before, their old login stayed valid until it expired (24 h). |
| 1.7 | Log out | ✅ | The app forgets the login. |
| 1.8 | Log out also cancels the login on the server | ❌ | Not built: a copied login token still works until it expires (24 h). |

## 2. Jobs and resumes

| # | Feature | Status | Notes |
|---|---|:-:|---|
| 2.1 | Upload a job description file (PDF, Word, text) | ✅ | Title, JD number (JD-0001…), required/nice-to-have skills and years of experience are read automatically. |
| 2.2 | Create a job from a list of skills (no file) | ✅ | |
| 2.3 | Warn when the same JD is uploaded twice | ✅ | "Upload again as a new job?" |
| 2.4 | Upload many resumes to a job | ✅ | A broken file doesn't fail the whole batch; duplicate file names are renamed. |
| 2.5 | Job list and job details | ✅ | Admin sees all jobs; an employee sees only their own. |
| 2.6 | Delete a job | ✅ | Removes the files and all its data. |
| 2.7 | Keep only the newest 100 jobs | ✅ | Older ones are removed automatically and the chat tells the user. |
| 2.8 | Download the original JD and resume files | ✅ | |

## 3. Resume screening

| # | Feature | Status | Notes |
|---|---|:-:|---|
| 3.1 | Score and rank every resume against the JD | ✅ | Rule-based, no AI: required skills 85 points, nice-to-have 15, ±5 for experience. Same input always gives the same score. |
| 3.2 | Read name, email, phone and years of experience from each resume | ✅ | |
| 3.3 | Screen again after adding resumes | ✅ | Bug found and fixed in Phase 9: the second screening of a job used to fail with an error. |
| 3.4 | Shortlist mark at 50 points or more | ✅ | |
| 3.5 | Download report | 🟡 | It is a **CSV** file (opens in Excel), not a true `.xlsx` file, although the button says "Download Excel". |
| 3.6 | Live progress text while screening | 🟡 | Always empty, because screening finishes instantly. |

## 4. AI recruiter assistant (chat / voice)

| # | Feature | Status | Notes |
|---|---|:-:|---|
| 4.1 | Chat with the assistant; it can start screening when asked | 🔑 | Fully built. Never seen answering with a real Gemini key. |
| 4.2 | Friendly chat reply when the AI is unavailable or no key is set | ✅ | Checked in the real app: the chat shows a normal message instead of an error. |

## 5. Settings (admin only)

| # | Feature | Status | Notes |
|---|---|:-:|---|
| 5.1 | Save / clear the Gemini API key | ✅ | Stored encrypted, shown only as `AIza…7890`. |
| 5.2 | Test the Gemini key | ✅ | Makes a real call to Google; a wrong key is correctly rejected. |
| 5.3 | Public URL for links, and invite expiry time | ✅ | Default expiry 48 hours. |
| 5.4 | Save and test Twilio details | ✅ | Token stored encrypted; a wrong login is correctly rejected by real Twilio. |
| 5.5 | Code-runner (Piston) address | 🟡 | Saved and checked, but nothing runs candidate code yet. |
| 5.6 | "Reset" button | 🟡 | Does nothing on purpose (there is no global chat session to clear); kept so the button doesn't error. |

## 6. Question bank (Settings page, admin only)

| # | Feature | Status | Notes |
|---|---|:-:|---|
| 6.1 | Upload questions from CSV, JSON or Excel | ✅ | Bad rows are skipped with a warning; good rows still load. |
| 6.2 | See totals by type and level, and the upload history | ✅ | |
| 6.3 | Delete one upload or clear the whole bank | ✅ | |

## 7. Online assessments

| # | Feature | Status | Notes |
|---|---|:-:|---|
| 7.1 | Build a test from the question bank | ✅ | |
| 7.2 | Build a test half from the bank, half by AI | 🔑 | If AI fails it quietly uses the bank only. |
| 7.3 | Build a test fully by AI | 🔑 | Without a working key it says so clearly. |
| 7.4 | Three levels (L1, L2, L3), 6 questions each; single-choice, multi-choice, coding and written questions | ✅ | |
| 7.5 | Answer key | 🟡 | The backend sends the correct key, but the **frontend popup shows "No assessment generated"** because of a small bug in `Candidates.jsx` (one line, not fixed — the brief was backend-only). |
| 7.6 | Create single-use, expiring links per level or combined | ✅ | Only a hash of each link's token is stored. |
| 7.7 | Candidate opens the link and takes the test | ❌ | No page exists in the frontend, and no backend endpoint for it. |
| 7.8 | Save the candidate's answers and mark them automatically | ❌ | Depends on 7.7. |
| 7.9 | Results table, pass/fail (50%), "assessments completed" on the dashboard | ❌ | Always shows zero submissions. |
| 7.10 | Proctoring flags (for example, leaving the tab) | ❌ | Depends on 7.7. |
| 7.11 | Scorecard PDF per candidate | ❌ | `GET /api/scorecard/…` is not built. |
| 7.12 | "Find any missing submission" button | ❌ | `GET /api/assessment/submissions_all` is not built. |

## 8. AI interviews

| # | Feature | Status | Notes |
|---|---|:-:|---|
| 8.1 | AI writes the interview questions from the JD and the candidate's resume, in the chosen language | 🔑 | 5 questions plus a welcome and a goodbye. |
| 8.2 | Create a self-service interview link (single-use, expiring) | 🔑 | Needs 8.1 to succeed first. |
| 8.3 | Candidate opens the link and sees the welcome page and questions | ✅ | Checked in the real frontend page. Speech is done by the browser. |
| 8.4 | Answers saved when the candidate finishes; the link then stops working | ✅ | Exactly one submission can win, even if the button is pressed many times at once. |
| 8.5 | HR runs an interview inside the app and saves it | ✅ | |
| 8.6 | List and read interview transcripts | ✅ | Checked in the real Candidates page. |
| 8.7 | Phone interview: place the call through Twilio | 🔑 | The request reaches real Twilio and is correctly rejected for the test credentials. No real call has been placed. |
| 8.8 | Phone interview: Twilio runs the conversation, each spoken answer saved | 🔑 | Verified by sending Twilio-style signed requests ourselves; never with a real call. |
| 8.9 | Live call status for the call-monitor screen | 🔑 | Same as 8.8. |
| 8.10 | Only real Twilio can trigger the phone webhooks | ✅ | Signature check, verified against Twilio's own published example. |

## 9. Dashboard and activity

| # | Feature | Status | Notes |
|---|---|:-:|---|
| 9.1 | Activity feed and notification bell | 🟡 | Built from existing dates (JD uploaded, resume uploaded, screening run). Assessment and interview events don't appear, and there is no dedicated activity table. |
| 9.2 | Dashboard and analytics numbers from screening | ✅ | The assessment numbers stay at zero (see 7.9). |

## 10. Security and reliability

| # | Feature | Status | Notes |
|---|---|:-:|---|
| 10.1 | Signed login tokens; admin and employee roles | ✅ | |
| 10.2 | Employees can only open and change their own jobs, on every endpoint | ✅ | Added in Phase 9. Before, anyone logged in who knew another job's ID could read or change it. |
| 10.3 | Secrets stored encrypted; link tokens stored only as hashes | ✅ | |
| 10.4 | Slow down repeated login attempts and link guessing | ✅ | Added in Phase 9: 10 login tries and 30 link tries per minute per address. |
| 10.5 | Request IDs in the logs; link tokens never written to logs | ✅ | Added in Phase 9. |
| 10.6 | Health check for monitoring | ✅ | `/actuator/health`, plus liveness and readiness. |
| 10.7 | Refuses to start in production with the default passwords | ✅ | Added in Phase 9 (`prod` profile). |
| 10.8 | Time limits on calls to Gemini/Twilio; clean shutdown | ✅ | Added in Phase 9. |
| 10.9 | Every error comes back in one format the frontend understands | ✅ | |
| 10.10 | Rate limits shared across several servers | ❌ | Limits are kept in memory, so they work for one server only (fine for this project's design). |
| 10.11 | Only an existing admin can create admin accounts | ❌ | Not so today: the sign-up form lets any visitor choose *Admin* and gets an admin login at once (the original app worked this way). Fix idea: allow admin sign-up only for a logged-in admin. |
| 10.12 | The candidate report download needs a login | ❌ | Not so today: `/api/download_report` returns the whole candidate list to anyone, because the frontend opens it as a plain link that can't carry a login. A proper fix needs a small frontend change. |

---

## Project quality (not counted in the scoreboard)

| Item | Status | Notes |
|---|:-:|---|
| Automated tests | ✅ | **242 tests, all passing:** 72 small unit tests and 170 integration tests that run the whole app against a real PostgreSQL database (started in Docker for each run). They cover every area above. The AI and phone features are tested against stand-in servers that imitate Gemini and Twilio — never the real services, and not the React frontend. Run them with `mvn test` (Docker must be running). |
| Documentation | ✅ | `FEATURES.md` (details), `FOLDER_STRUCTURE.md` (code layout) and this file (status). |
| Starts on a clean machine | ✅ | `docker compose up -d`, then `java -jar target/backend-classic-0.1.0.jar`. |
| Saved in git | ❌ | Nothing in `backend-classic/` has been committed yet. |

## What the tests found and fixed along the way

Writing the automated tests turned up real bugs that hand-testing had missed:

| Problem found | Effect for a real user | Status |
|---|---|---|
| Screening the same job a second time failed | "Add more resumes, then screen again" showed an error | Fixed |
| Sending an interview link failed on save | Every "send AI interview link" click would have failed, even with a working Gemini key | Fixed |
| Employees could open or change other people's jobs if they knew the ID | A privacy/permission hole | Fixed (all 17 job endpoints) |
| A removed employee's old login kept working for up to 24 hours | Access after removal | Fixed |
| Phone-call text had no declared character set | Hindi/Tamil interview questions could be garbled for the caller | Fixed |
| Some admin-only screens answered non-admins with the wrong error | Confusing message only | Fixed |

## What is still needed to call it complete

1. **Build the candidate side of assessments** (the only big gap): a page where a candidate opens the link and takes the test, endpoints to save answers and mark them, the results table, the scorecard PDF, and `submissions_all` (features 7.7 – 7.12).
2. **Close the two security gaps** — admin sign-up open to anyone, and the candidate report link that works without a login (10.11, 10.12).
3. **Try the AI features once with a real Gemini key** (Settings → paste key). This turns 🔑 into ✅ for 4.1, 7.2, 7.3, 8.1 and 8.2.
4. **Place one real phone call** (Twilio account + public tunnel such as ngrok, entered in Settings). This turns 🔑 into ✅ for 8.7 – 8.9.
5. **Fix the two small frontend bugs** in `Candidates.jsx` (Answer Key popup, and the job switcher using an old job id) — 7.5.
6. Optional polish: real `.xlsx` report (3.5), interview/assessment events in the activity feed (9.1), server-side logout (1.8).
7. Commit the work to git.

## Other things to know

- **Frontend-side gaps** were only documented, never changed, because the brief was "backend only, no frontend changes". See "Bugs found" in `FEATURES.md`.
- **Public links by design:** file downloads, interview links and Twilio webhooks are reachable without a login; each is protected in its own way (unguessable IDs, single-use hashed tokens, request signatures).
- **Same score every time:** screening never uses AI, so results are repeatable and explainable.
- **Shared browsers:** the frontend remembers the last "active job" in the browser, even after someone else logs in. If an employee then signs in on a browser where an admin was working, the background checks for that job are now refused ("no permission", 403) instead of leaking the admin's data. The dashboard just shows zeros until they pick their own job. Checked in the real app; the frontend should clear that remembered job on login (a small frontend fix, not done).
