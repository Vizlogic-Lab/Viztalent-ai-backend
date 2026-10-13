# Viztalent AI — Final Deployment Guide (Hostinger VPS + Dokploy)

This is the complete, end-to-end guide to put Viztalent AI live on your Hostinger VPS
using Dokploy, and to keep it running afterwards. Follow it top to bottom.
Every value you need to type is in here.

| Item | Value |
|---|---|
| VPS IP | `200.234.34.13` |
| Dokploy panel | `http://200.234.34.13:3000` (move it to HTTPS — Step 1.4) |
| Backend repo | `https://github.com/Vizlogic-Lab/Viztalent-ai-backend` (branch `main`) |
| Frontend repo | `https://github.com/Vizlogic-Lab/Viztalent-ai-frontend` (branch `main`) |
| Backend | Spring Boot 3.3, Java 21, port **8000** |
| Frontend | React + Vite, served by nginx, port **80** |
| Database | PostgreSQL **16** |
| Code runner | Piston (only needed for **coding** questions) |

Throughout this guide, replace `viztalent.example.com` with **your real domain**.

---

## 0. How it fits together

```
Candidate / Recruiter browser
        │  https://viztalent.example.com
        ▼
 Dokploy's Traefik (HTTPS, Let's Encrypt)
        ├── path /      → viztalent-frontend  (nginx, port 80)
        └── path /api   → viztalent-backend   (Spring Boot, port 8000)
                              ├── viztalent-db        (PostgreSQL 16, internal only)
                              ├── /app/storage        (persistent volume: résumés, JDs)
                              ├── viztalent-piston    (code runner, internal only)
                              ├── Google Gemini API   (AI questions, chat)
                              ├── SMTP server         (emails)
                              └── Twilio              (phone interviews, optional)
```

One domain serves both apps. The browser calls `/api/...` on the same domain, so there
are no CORS problems and no separate API domain.

---

## 1. Prepare (one time, before touching Dokploy)

### 1.1 Domain and DNS
1. Decide your domain, e.g. `viztalent.example.com`.
2. At your domain provider (Hostinger → Domains → DNS / Nameservers), add:

   | Type | Name | Value | TTL |
   |---|---|---|---|
   | A | `viztalent` (or `@` for the root domain) | `200.234.34.13` | 300 |

3. Wait until it resolves (usually 5–30 minutes). Check from your laptop:
   ```bash
   nslookup viztalent.example.com
   ```
   It must answer `200.234.34.13`. **Do not continue until it does** — the HTTPS
   certificate can only be issued once DNS points at the VPS.

### 1.2 Log in to the VPS
```bash
ssh root@200.234.34.13
```
(Hostinger panel → VPS → SSH access shows the root password / lets you add an SSH key.)

### 1.3 Firewall
Only these ports should be open to the internet:

| Port | Why |
|---|---|
| 22 | SSH |
| 80 | HTTP (Let's Encrypt + redirect to HTTPS) |
| 443 | HTTPS |
| 3000 | Dokploy panel — **close it after Step 1.4** |

In the Hostinger panel: VPS → Security → Firewall → allow 22, 80, 443 (and 3000 for now).
Never open 5432 (Postgres), 8000 (backend) or 2000 (Piston) to the internet.

### 1.4 Secure the Dokploy panel (important)
Right now the panel is plain `http://` on a public IP, so your Dokploy password travels
unencrypted.
1. Add a second DNS record: `A  dokploy  →  200.234.34.13`.
2. Dokploy → **Settings → Web Server / Server Domain** → set `dokploy.example.com`,
   enable HTTPS (Let's Encrypt), save.
3. Open `https://dokploy.example.com`, confirm it works, then remove port **3000** from
   the firewall.

### 1.5 Check disk and memory
```bash
df -h /        # want at least 10 GB free
free -h        # 4 GB RAM or more recommended (frontend build needs ~1 GB while building)
```

### 1.6 Generate the production secrets
Run on the VPS (or any Linux/Mac terminal) and **save the output in a password manager**:
```bash
echo "DB_PASSWORD=$(openssl rand -hex 24)"
echo "APP_JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n')"
echo "SETTINGS_ENCRYPTION_KEY=$(openssl rand -base64 48 | tr -d '\n')"
```

Rules the backend enforces (it **refuses to start** if they're broken):
- `APP_JWT_SECRET` — at least 32 bytes, not a development default.
- `SETTINGS_ENCRYPTION_KEY` — at least 24 characters, not a development default.
- `DB_PASSWORD` — present and not a development default.

⚠️ **Never lose `SETTINGS_ENCRYPTION_KEY`.** It encrypts the Gemini key, SMTP password
and Twilio token saved in Settings. If it changes, those must all be re-entered.
⚠️ Never reuse the values from the local development `.env` — the backend rejects them.

### 1.7 Choose the first admin's login
- Email: your real admin email.
- Temporary password: **at least 10 characters, at least one letter and one digit**
  (e.g. generate one with `openssl rand -base64 12` and add a digit if needed).
  A password that breaks this rule stops the backend from starting.

---

## 2. Create the project in Dokploy

Dokploy → **Projects → Create Project** → name **`Viztalent`** → Create.

All services below are created inside this project.

---

## 3. Database (PostgreSQL 16)

1. In the `Viztalent` project → **Create Service → Database → PostgreSQL**.
2. Fill in:

   | Field | Value |
   |---|---|
   | Name | `viztalent-db` |
   | Database name | `viztalent` |
   | Database user | `viztalent` |
   | Database password | the `DB_PASSWORD` from Step 1.6 |
   | Docker image | `postgres:16` |

3. **Create**, then **Deploy**. Wait for status **Running**.
4. On the database page, copy the **Internal Host** (looks like
   `viztalent-viztalentdb-abc123`). You need it for `DB_URL`.
5. Leave **External Port empty** — the database must never be public.

The schema is created automatically: the backend runs its Flyway migrations on start.

---

## 4. Backend (`viztalent-backend`)

### 4.1 Create
**Create Service → Application** → name `viztalent-backend`.

### 4.2 Source
| Field | Value |
|---|---|
| Provider | GitHub (connect the Vizlogic-Lab organisation if asked) |
| Repository | `Vizlogic-Lab/Viztalent-ai-backend` |
| Branch | `main` |
| Build type | **Dockerfile** |
| Dockerfile path | `Dockerfile` |

### 4.3 Environment variables
Tab **Environment**, paste (replace the `<…>` values):

```env
DB_URL=jdbc:postgresql://<INTERNAL-HOST-FROM-STEP-3>:5432/viztalent
DB_USERNAME=viztalent
DB_PASSWORD=<DB_PASSWORD>
APP_JWT_SECRET=<APP_JWT_SECRET>
SETTINGS_ENCRYPTION_KEY=<SETTINGS_ENCRYPTION_KEY>
CORS_ORIGINS=https://viztalent.example.com
PUBLIC_BASE_URL=https://viztalent.example.com
RATE_LIMIT_TRUST_FORWARDED_FOR=true
BOOTSTRAP_ADMIN_EMAIL=<your admin email>
BOOTSTRAP_ADMIN_PASSWORD=<temporary admin password>
BOOTSTRAP_ADMIN_NAME=<your name>
```

Already built into the image — **do not add**: `SPRING_PROFILES_ACTIVE=prod`,
`STORAGE_DIR=/app/storage`.

Why the important ones matter:
- `PUBLIC_BASE_URL` — the address put in candidate assessment/interview links and Twilio
  callbacks. Without it (and with nothing saved in Settings) links point to `localhost` and
  candidates can't open them.
- `RATE_LIMIT_TRUST_FORWARDED_FOR=true` — behind Traefik every request arrives from the
  same internal IP. Without it, **the whole company shares 10 login attempts per minute**.
- `BOOTSTRAP_ADMIN_*` — production has **no demo accounts**. These create the first
  admin once. **Remove them after the first successful start** (Step 8.3).

Optional variables (sensible defaults exist):

| Variable | Default | Use |
|---|---|---|
| `GEMINI_API_KEY` | – | AI key (or paste it in Settings later) |
| `GEMINI_MODEL` / `GEMINI_FALLBACK_MODEL` | – | Starting model names (choose in Settings) |
| `AI_DAILY_CALL_CAP` | `2000` | Max Gemini calls per day (cost guard) |
| `SMTP_PASSWORD` | – | Overrides the SMTP password saved in Settings |
| `JWT_TTL_MINUTES` | `1440` | How long a login lasts (24 h) |
| `RATE_LIMIT_TRUSTED_PROXIES` | `1` | Set `2` only if you put Cloudflare in front of the VPS |
| `RATE_LIMIT_AUTH_PER_MINUTE` | `10` | Login/sign-up attempts per IP per minute |
| `RATE_LIMIT_TOKEN_PER_MINUTE` | `30` | Candidate-link requests per IP per minute |
| `ASSESSMENT_RUN_LIMIT` | `20` | "Run code" presses per question per candidate |
| `RUNNER_MAX_CONCURRENCY` | `8` | Parallel code runs |

### 4.4 Persistent storage (do not skip)
Tab **Advanced → Volumes / Mounts → Add Volume**:

| Field | Value |
|---|---|
| Type | Volume |
| Volume name | `viztalent-storage` |
| Mount path | `/app/storage` |

Résumés and JDs are stored here, **not** in the database. Without this volume every
redeploy deletes them.

### 4.5 Domain
Tab **Domains → Add Domain**:

| Field | Value |
|---|---|
| Host | `viztalent.example.com` |
| Path | `/api` |
| **Strip Path** | **OFF** (the backend's routes start with `/api`) |
| Container port | `8000` |
| HTTPS | ON |
| Certificate | Let's Encrypt |

### 4.6 Deploy
Click **Deploy** and open **Logs**. A good start shows, in order:
```
The following 1 profile is active: "prod"
Successfully applied N migrations ...
Bootstrap admin created for <your email>; they must change the password at first login.
Started BackendClassicApplication in ~15 seconds
```
The container's health turns **healthy** within about a minute.

If it stops instead:

| Log message | Fix |
|---|---|
| `Refusing to start with the 'prod' profile, insecure configuration: …` | The message names the bad secret — regenerate it (Step 1.6). |
| `BOOTSTRAP_ADMIN_PASSWORD: Password must …` | Use 10+ chars with a letter and a digit. |
| `Set both BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD, or neither.` | Set both. |
| `Connection refused` / `UnknownHostException` for the DB | Wrong Internal Host in `DB_URL`, or the database isn't running. |
| `password authentication failed` | `DB_PASSWORD` differs from the database's password. |

---

## 5. Frontend (`viztalent-frontend`)

1. **Create Service → Application** → name `viztalent-frontend`.
2. Source:

   | Field | Value |
   |---|---|
   | Repository | `Vizlogic-Lab/Viztalent-ai-frontend` |
   | Branch | `main` |
   | Build type | **Dockerfile** |
   | Dockerfile path | `Dockerfile` |

3. **Do not set `VITE_API_BASE`.** Leave it empty so the app calls `/api` on its own domain.
4. **Domains → Add Domain**:

   | Field | Value |
   |---|---|
   | Host | `viztalent.example.com` |
   | Path | `/` |
   | Container port | `80` |
   | HTTPS | ON, Let's Encrypt |

5. **Deploy**. The build takes a few minutes (it installs packages and builds the app).

---

## 6. Code runner — Piston (for coding questions)

Needed only if your assessments contain **coding questions** ("Run code", automatic
marking of code). MCQ, written and spoken questions work without it.

Piston needs Docker's `privileged` mode, which Dokploy's **Application** type cannot
provide — so it is deployed as a **Compose** service.

### 6.1 Create
**Create Service → Compose** → name `viztalent-piston` → type **Docker Compose** →
provider **Raw** (paste the file below) → Save → **Deploy**.

```yaml
services:
  piston:
    image: ghcr.io/engineer-man/piston
    privileged: true            # required by Piston's sandbox
    restart: unless-stopped
    environment:
      PISTON_DISABLE_NETWORKING: "true"      # candidate code gets no internet
      PISTON_RUN_TIMEOUT: "3000"
      PISTON_RUN_CPU_TIME: "3000"
      PISTON_COMPILE_TIMEOUT: "10000"
      PISTON_RUN_MEMORY_LIMIT: "268435456"   # 256 MB
      PISTON_OUTPUT_MAX_SIZE: "65536"        # 64 KB
      PISTON_MAX_PROCESS_COUNT: "64"
    tmpfs:
      - /tmp:exec
    volumes:
      - piston_packages:/piston/packages
    networks:
      dokploy-network:
        aliases:
          - viztalent-piston
volumes:
  piston_packages:
networks:
  dokploy-network:
    external: true
```

No domain and no published port — it is reachable only inside Dokploy's network as
`viztalent-piston`.

### 6.2 Install the languages (one time)
On the VPS (SSH), run:
```bash
for pkg in java python node gcc; do
  echo "Installing $pkg..."
  docker run --rm --network dokploy-network curlimages/curl:8.10.1 -sS -f \
    -X POST -H 'Content-Type: application/json' \
    -d "{\"language\":\"$pkg\",\"version\":\"*\"}" \
    http://viztalent-piston:2000/api/v2/packages
  echo
done

# Check:
docker run --rm --network dokploy-network curlimages/curl:8.10.1 -sS \
  http://viztalent-piston:2000/api/v2/runtimes
```
The last command must list java, python, node (javascript) and gcc (c/c++). Languages
are kept in the `piston_packages` volume, so this survives restarts.

You connect the backend to it in Step 9.4.

---

## 7. Smoke test (right after deploying)

Open these in a browser:

| Check | Expected |
|---|---|
| `https://viztalent.example.com` | Login page, valid padlock (no certificate warning) |
| `https://viztalent.example.com/candidates` (direct) | The app loads (not a 404) |
| `https://viztalent.example.com/api/progress` | JSON `401 Authentication required` → `/api` reaches the backend |

If the domain shows a certificate error, DNS wasn't pointing at the VPS when you added
the domain — confirm Step 1.1, then in Dokploy remove and re-add the domain.

---

## 8. First login and lock-down

1. Open `https://viztalent.example.com`, choose **Admin**, log in with the
   `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` from Step 4.3.
2. You are asked to **change the password** — set a strong permanent one and store it in
   your password manager.
3. In Dokploy → `viztalent-backend` → Environment: **delete** `BOOTSTRAP_ADMIN_EMAIL` and
   `BOOTSTRAP_ADMIN_PASSWORD` → Save → **Redeploy**.

---

## 9. Configure the application (as admin → Settings)

### 9.1 Public backend URL (mandatory)
Set it to **`https://viztalent.example.com`** and save.
Every candidate link (assessment, interview) and every Twilio callback is built from this
value. If it is wrong, candidates receive broken links.

### 9.2 Gemini (AI)
1. Create a **new** API key at https://aistudio.google.com (do not reuse any key that was
   ever pasted into a chat or a file).
2. Settings → AI Provider → paste the key → **Save & test**.
3. Choose the model and an optional fallback model in Settings; test again.
4. Billing: Google AI Studio → set a budget alert on the project that owns the key.

### 9.3 Email (SMTP)
Settings → Email: host, port, username, password, "from" address → **send a test email**.
- Gmail / Google Workspace: `smtp.gmail.com`, port `587` (STARTTLS), and an **App
  Password** (Google Account → Security → App passwords), not your normal password.
- Hostinger Email: `smtp.hostinger.com`, port `465` (SSL) or `587`.

### 9.4 Code runner (if you did Step 6)
Settings → Code runner (Piston) URL: **`http://viztalent-piston:2000/api/v2`** → Save
→ it should show the installed languages.

### 9.5 Twilio phone interviews (optional)
1. Twilio console: buy a phone number, copy **Account SID** and **Auth Token**.
2. Settings → Twilio: SID, token, number → Save.
3. Trial accounts can only call **verified** numbers and play a trial message —
   upgrade the account before calling real candidates.
4. Twilio calls `https://viztalent.example.com/api/interview/twiml/...`, so Step 9.1 must
   be correct; requests are verified with Twilio's signature.

### 9.6 Other settings
- Invite link expiry (default 48 hours).
- Proctoring settings (camera, microphone, face/phone detection).
- Scoring settings per job.
- Create employee and reviewer accounts (Employees page).

---

## 10. End-to-end acceptance test

Do the whole hiring flow once before announcing the launch:

1. **Jobs** → create a job (upload a JD, or paste the skills).
2. **Screening** → upload 2–3 résumés → run screening → candidates are ranked.
3. Generate the assessment for the job (AI / question bank / mix).
4. **Candidates** → send an assessment invite **to yourself**.
5. Open the email on a **phone using mobile data** (proves it works from outside your
   network). The link must start with `https://viztalent.example.com/assessment/...`.
6. Allow camera + microphone (only works on HTTPS — that's why the domain matters).
7. Answer, run a coding question if you have one, submit.
8. Back as recruiter: the result, score and scorecard (PDF) appear.
9. Open the same link again → it must refuse (links are single-use).
10. **Persistence check**: redeploy `viztalent-backend` → the uploaded résumés and the
    result are still there (proves the volume and database are persistent).
11. If you use phone interviews: call a verified number; answers are saved and the call
    status ends as completed.

When all eleven pass, you are live.

---

## 11. Backups (set up in the first week)

### 11.1 Database
Dokploy → `viztalent-db` → **Backups**:
1. Add an S3-compatible destination (Dokploy → Settings → S3 Destinations): Cloudflare
   R2, Backblaze B2, AWS S3, or Hostinger Object Storage.
2. Schedule: **daily**, keep at least **14** backups.

### 11.2 Uploaded files (`/app/storage`)
Résumés are not in the database. Back the volume up daily on the VPS:
```bash
mkdir -p /root/backups
cat > /root/backup-storage.sh <<'EOF'
#!/bin/sh
set -e
STAMP=$(date +%F)
docker run --rm -v viztalent-storage:/data -v /root/backups:/backup alpine \
  tar czf /backup/storage-$STAMP.tar.gz -C /data .
find /root/backups -name 'storage-*.tar.gz' -mtime +14 -delete
EOF
chmod +x /root/backup-storage.sh
(crontab -l 2>/dev/null; echo "30 2 * * * /root/backup-storage.sh") | crontab -
```
Check the real volume name first with `docker volume ls | grep storage` and use it in the
script. Copy `/root/backups` off the server regularly (e.g. `rclone` to the same S3
bucket) — a backup that lives only on the same VPS dies with the VPS.

### 11.3 Test a restore (once)
- Database: Dokploy → Backups → Restore into a new test database, check the data is there.
- Files:
  ```bash
  docker run --rm -v viztalent-storage:/data -v /root/backups:/backup alpine \
    sh -c "cd /data && tar xzf /backup/storage-YYYY-MM-DD.tar.gz"
  ```
An untested backup is not a backup.

### 11.4 Keep safe (outside the VPS)
- The three secrets from Step 1.6 (especially `SETTINGS_ENCRYPTION_KEY`).
- The admin password, Dokploy password, Gemini/SMTP/Twilio credentials.

---

## 12. Monitoring

1. **Uptime**: a free monitor (e.g. UptimeRobot) on
   - `https://viztalent.example.com` → expect 200
   - `https://viztalent.example.com/api/progress` → expect **401** (backend alive)
2. **Dokploy notifications**: Settings → Notifications → email/Telegram/Slack/Discord for
   failed deploys and container restarts.
3. **Disk**: `df -h` weekly; uploads, Docker images and backups grow over time.
   Clean old images: `docker image prune -a` (only removes unused images).
4. **Gemini usage**: Settings shows AI usage; `AI_DAILY_CALL_CAP` limits daily calls.
5. **Logs**: Dokploy → service → Logs. Each backend log line has a request id
   (`[abc123]`) — the same id is returned to the browser in the `X-Request-Id` header,
   so you can match a user's error to the exact log lines.

---

## 13. Updating the app later

1. Push changes to `main` on GitHub.
2. Dokploy → the service → **Deploy** (or enable **Auto Deploy** on the service so every
   push to `main` deploys automatically).
3. Database changes are applied automatically by Flyway when the backend starts.
   ⚠️ Never edit a migration file that has already been deployed
   (`src/main/resources/db/migration/V*.sql`) — add a new `V<next>__*.sql` instead,
   otherwise the backend refuses to start.
4. Watch the logs until `Started BackendClassicApplication` and health is **healthy**.

### Rollback
Dokploy → service → **Deployments** → pick the previous successful deployment →
**Rollback**. Note: a rollback does not undo database migrations — that's why each
migration should only add things (new tables/columns), never remove what older code needs.

---

## 14. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Browser shows certificate warning | DNS wasn't pointing at the VPS when the domain was added | Fix DNS (1.1), remove and re-add the domain in Dokploy |
| `/api/...` returns the login page HTML / 404 | Backend domain path wrong or Strip Path ON | Backend domain: path `/api`, Strip Path **OFF**, port 8000 |
| Every API call fails with CORS error | `CORS_ORIGINS` doesn't match the domain exactly | `CORS_ORIGINS=https://viztalent.example.com` (no trailing slash), redeploy |
| "Too many requests" for everyone | `RATE_LIMIT_TRUST_FORWARDED_FOR` not `true` | Set it, redeploy |
| Backend keeps restarting | Failing start — read the first error in Logs | See the table in Step 4.6 |
| Résumés gone after a redeploy | No volume on `/app/storage` | Step 4.4 (files already lost can only come from a backup) |
| Candidate link opens `localhost`/old ngrok address | Public backend URL not set | Settings → Public backend URL (Step 9.1), then send a new invite |
| Camera/mic never asks for permission | Opened over `http://` or by IP | Always use `https://viztalent.example.com` |
| AI features say unavailable | No/invalid Gemini key, model not available, or daily cap reached | Settings → AI Provider → test key, pick another model; check `AI_DAILY_CALL_CAP` |
| "Run code" does nothing / errors | Piston not deployed, languages not installed, or wrong URL | Step 6 and Step 9.4 |
| Emails not arriving | SMTP settings, or mail lands in spam | Send a test from Settings; use an App Password; add SPF/DKIM for your sending domain |
| Saved Gemini/SMTP/Twilio secrets stopped working | `SETTINGS_ENCRYPTION_KEY` changed | Restore the original key, or re-enter the secrets |
| Frontend build fails in Dokploy | Low memory during `npm run build` | Ensure ~1 GB free RAM, retry; add swap if needed |

Add 2 GB of swap if the VPS has little RAM:
```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

---

## 15. Known limits (fine for launch)

- **One backend container only.** Rate limits are kept in memory; don't scale the backend
  to 2+ replicas without moving them to a shared store.
- **Files live on the VPS disk** (`/app/storage`). Move to S3/MinIO if disk becomes tight.
- **Phone interviews** have not been run against a real Twilio call yet — test them
  (Step 10.11) before offering them to customers.
- **Interview video recording** is designed (`docs/INTERVIEW_CHANNELS_DESIGN.md`) but not
  built.
- **Piston runs privileged** on a VPS shared with your other projects. It has no domain
  and no published port; candidate code runs without network access. If you don't use
  coding questions, skip Step 6.

---

## 16. Final checklist

**Before deploying**
- [ ] DNS A record → `200.234.34.13`, resolves (1.1)
- [ ] Firewall: 22, 80, 443 only (1.3); Dokploy panel on HTTPS, port 3000 closed (1.4)
- [ ] Secrets generated and saved in a password manager (1.6)

**Dokploy**
- [ ] Project `Viztalent` (2)
- [ ] `viztalent-db` PostgreSQL 16 running, no external port (3)
- [ ] `viztalent-backend`: env vars, volume `/app/storage`, domain `/api` Strip Path OFF port 8000, healthy (4)
- [ ] `viztalent-frontend`: domain `/` port 80 (5)
- [ ] `viztalent-piston` + languages installed — only if coding questions (6)

**After deploying**
- [ ] Smoke test passes (7)
- [ ] Admin logged in, password changed, `BOOTSTRAP_ADMIN_*` removed, redeployed (8)
- [ ] Settings: Public URL, new Gemini key, SMTP test, Piston URL, Twilio if used (9)
- [ ] Full acceptance test, all 11 steps (10)
- [ ] Database + storage backups scheduled, one restore tested (11)
- [ ] Uptime monitor + Dokploy notifications (12)

When every box is ticked, Viztalent AI is live in production.
