# Go-Live Checklist — Viztalent AI on Dokploy

Server: Hostinger VPS `200.234.34.13`, Dokploy panel on `:3000`.
Repos: `Vizlogic-Lab/Viztalent-ai-backend` and `Vizlogic-Lab/Viztalent-ai-frontend`.

Target layout — **one domain**, Dokploy (Traefik) routes by path:

```
https://<your-domain>/        → frontend container (nginx, port 80)
https://<your-domain>/api/... → backend container (Spring Boot, port 8000)
backend → Dokploy PostgreSQL 16 (internal network)
backend → /app/storage (persistent volume: résumés, JDs, uploads)
```

Tick each box in order. ⚠️ = will break the deployment if skipped.

---

## Phase 0 — Before you touch Dokploy

### Code on GitHub
- [ ] ⚠️ **Backend:** commit all local work and push to `main` (16 unpushed commits + uncommitted work: proctoring, bootstrap admin, password policy, migrations V26/V28/V29, Dockerfile fixes). Dokploy builds from GitHub — unpushed code is not deployed.
- [ ] ⚠️ **Frontend:** commit and push (9 unpushed commits + 19 uncommitted files).
- [ ] Backend `mvn test` is green (it was 497/497 after the masking-test fix).
- [ ] `.env` is **not** committed (it's in `.gitignore`). Only `.env.example` may be.

### Domain & DNS
- [ ] Choose the domain, e.g. `viztalent.yourcompany.com`.
- [ ] ⚠️ Create a DNS **A record** → `200.234.34.13`. Wait until `nslookup <your-domain>` returns that IP.

### Generate production secrets (on the VPS, never reuse the dev `.env`)
```bash
openssl rand -base64 64   # → APP_JWT_SECRET
openssl rand -base64 48   # → SETTINGS_ENCRYPTION_KEY
openssl rand -base64 32   # → DB password
```
- [ ] Store them in a password manager. ⚠️ Losing `SETTINGS_ENCRYPTION_KEY` means every secret saved in Settings (Gemini key, SMTP, Twilio) must be re-entered.
- [ ] Note: the `prod` profile **refuses to start** with weak or dev-default secrets — that's intentional.

### Firewall / VPS hygiene
- [ ] Open only ports **22, 80, 443** (and 3000 only if you must reach the panel by IP).
- [ ] ⚠️ Give the Dokploy panel its own HTTPS domain (Dokploy → Settings → Server domain), then close port 3000. Today the panel login travels over plain HTTP.
- [ ] Check free disk space (`df -h`) — Docker images + uploads need room (≥ 10 GB free recommended).

---

## Phase 1 — Database

- [ ] Dokploy → Create Project → **Viztalent**.
- [ ] In the project → **Create Service → Database → PostgreSQL**, version **16**.
  - Database name: `viztalent`, user: `viztalent`, password: the generated DB password.
- [ ] Deploy it; status should be **running**.
- [ ] Copy the **Internal Host** shown on the database page (used in `DB_URL`).
- [ ] Do **not** expose the database port publicly.

---

## Phase 2 — Backend

**Create Service → Application** → name `viztalent-backend`.

### Source & build
- [ ] Provider: GitHub → `Vizlogic-Lab/Viztalent-ai-backend`, branch `main`.
- [ ] Build type: **Dockerfile** (path `Dockerfile`).

### Environment variables
```
DB_URL=jdbc:postgresql://<internal-host>:5432/viztalent
DB_USERNAME=viztalent
DB_PASSWORD=<db password>
APP_JWT_SECRET=<generated>
SETTINGS_ENCRYPTION_KEY=<generated>
CORS_ORIGINS=https://<your-domain>
RATE_LIMIT_TRUST_FORWARDED_FOR=true
BOOTSTRAP_ADMIN_EMAIL=<your admin email>
BOOTSTRAP_ADMIN_PASSWORD=<strong temporary password>
BOOTSTRAP_ADMIN_NAME=<your name>
```
- [ ] `SPRING_PROFILES_ACTIVE=prod` and `STORAGE_DIR=/app/storage` are already set inside the image — no need to add.
- [ ] ⚠️ `RATE_LIMIT_TRUST_FORWARDED_FOR=true` — behind Traefik every request comes from one IP; without this **all users share 10 logins/minute**.
- [ ] ⚠️ `BOOTSTRAP_ADMIN_*` — production has **no demo logins**. This creates the first admin (must change password at first login). **Remove these two variables after the first successful start.**
- [ ] Optional: `GEMINI_API_KEY` (or paste it later in Settings), `SMTP_PASSWORD`.

### Persistent storage
- [ ] ⚠️ **Advanced → Volumes / Mounts → Volume**, mount path **`/app/storage`**. Without it every redeploy deletes all uploaded résumés and JDs.

### Domain
- [ ] **Domains → Add**: host `<your-domain>`, **path `/api`**, container port **8000**, HTTPS **on**, certificate **Let's Encrypt**.
- [ ] ⚠️ **Strip Path: OFF.** The backend's routes start with `/api`; stripping it breaks every call.

### Deploy & verify
- [ ] Deploy. Logs should show `The following 1 profile is active: "prod"` and `Started BackendClassicApplication`.
- [ ] Logs show `Bootstrap admin created for …`.
- [ ] Container health = **healthy** (health check hits `/actuator/health`).
- [ ] If it stops with `Refusing to start with the 'prod' profile…`, a secret is weak/missing — the message names which one.

---

## Phase 3 — Frontend

**Create Service → Application** → name `viztalent-frontend`.

- [ ] Provider: GitHub → `Vizlogic-Lab/Viztalent-ai-frontend`, branch `main`.
- [ ] Build type: **Dockerfile**.
- [ ] Build arg `VITE_API_BASE`: **leave empty** (the app then calls `/api` on its own domain, which Traefik routes to the backend — no CORS issues).
- [ ] **Domains → Add**: host `<your-domain>`, **path `/`**, container port **80**, HTTPS **on**, Let's Encrypt.
- [ ] Deploy; wait for the build (Vite build needs ~1 GB RAM on the VPS).

---

## Phase 4 — Smoke test (in a browser)

- [ ] `https://<your-domain>` loads with a valid padlock (no certificate warning).
- [ ] `https://<your-domain>/api/progress` returns a JSON `401 Authentication required` → the `/api` route reaches the backend. (Actuator health lives at `/actuator/health`, outside `/api`, so it is **not** public — only Docker's health check uses it.)
- [ ] Refresh directly on `https://<your-domain>/candidates` → the app loads (no 404).
- [ ] Log in with the bootstrap admin → you're forced to change the password → change it.
- [ ] Remove `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` from the backend env and redeploy.
- [ ] Create a job (paste skills), upload a résumé, run screening → candidate ranked.
- [ ] Redeploy the backend once → the uploaded résumé is **still there** (proves the volume works).

---

## Phase 5 — Configure the app (Settings page, as admin)

- [ ] ⚠️ **Public backend URL** → `https://<your-domain>`. Candidate assessment/interview links and Twilio callbacks are built from this. (Replace the old `abc123.ngrok-free.app` placeholder.)
- [ ] **Gemini API key** → paste a **new** key (rotate the one shared in chat). Choose the model in Settings; click Test.
- [ ] **Email (SMTP)** → host, port, user, password; send a test email.
- [ ] **Twilio** (only if using phone interviews) → Account SID, Auth Token, phone number.
- [ ] Invite expiry, scoring settings — review the defaults.
- [ ] Create employee / reviewer accounts.

---

## Phase 6 — End-to-end candidate test

- [ ] Send yourself an assessment invite → the email link opens `https://<your-domain>/assessment/...` (not localhost/ngrok).
- [ ] Open it on a **phone on mobile data** (proves it works from outside your network).
- [ ] ⚠️ Camera + microphone permission prompt appears (proctoring needs HTTPS — works only on the real domain).
- [ ] Complete and submit → result + scorecard appear for the recruiter.
- [ ] The link can't be reused (single-use token).
- [ ] Phone interview (if Twilio set up): place a call to a **verified** number; answers are saved and status becomes COMPLETED. Trial accounts can only call verified numbers.

---

## Phase 7 — Operations (do in week one)

### Backups
- [ ] Dokploy → database → **Backups**: schedule daily `pg_dump` to S3-compatible storage (keep ≥ 7 days).
- [ ] Back up the `/app/storage` volume too (résumés live there, not in the DB) — e.g. a daily `tar` of the volume to the same bucket.
- [ ] ⚠️ **Test a restore once.** An untested backup is not a backup.

### Monitoring
- [ ] Uptime check (e.g. UptimeRobot, free) on `https://<your-domain>` (frontend) and `https://<your-domain>/api/progress` expecting HTTP **401** (backend up).
- [ ] Dokploy notifications (email/Slack/Telegram) for failed deploys.
- [ ] Watch disk usage — uploads grow over time.

### Deploy workflow
- [ ] Enable **auto-deploy on push** (GitHub webhook) for both apps — or deploy manually.
- [ ] Database migrations (Flyway) run automatically on backend start — never edit an already-applied migration file.
- [ ] Keep the old image to roll back (Dokploy → Deployments → Rollback).

---

## Known limitations (acceptable for launch, track them)

- Rate limits are in-memory → correct for **one** backend container only. Don't scale to 2+ replicas without changing this.
- The rate limiter trusts the **first** `X-Forwarded-For` entry, which a client can spoof to dodge limits. Fine to launch; harden later (use the proxy-appended value).
- Phone interviews have never run against a **real** Twilio call — test before promising it to customers.
- Interview **video** recording is designed (`INTERVIEW_CHANNELS_DESIGN.md`) but not built.
- Uploads on a single volume — move to S3/MinIO if the VPS disk becomes a concern.

---

## Quick reference — what was already fixed for this deploy

| Problem | Fix |
|---|---|
| Image would not build (`useradd -u 1000` clashes with the base image's `ubuntu` user) | Non-root user now UID 10001 |
| Health check used `wget` (not installed) → container restart loop | `curl` installed; health check uses it, 60 s start period |
| `prod` profile not on by default | `ENV SPRING_PROFILES_ACTIVE=prod` in the image |
| Upload folder not prepared for a volume | `/app/storage` created, owned by the app user |
| Stale Twilio masking test failed the build | Test updated to the stricter `****1234` masking |

Verified locally: image builds, runs as `prod`, reports **healthy**, runs as non-root, `/app/storage` writable.
