# SmartStaff — Architecture & ER Diagrams

Mermaid diagrams for onboarding. Paste any block into the Mermaid Live Editor
(https://mermaid.live) or view in any Markdown renderer that supports Mermaid.

Companion to [ONBOARDING.md](ONBOARDING.md).

---

## 1. System context (who talks to what)

```mermaid
flowchart LR
    admin([Admin]):::person
    emp([Employee]):::person
    cand([Candidate]):::person

    subgraph client[Frontend - React 19 + Vite :5173]
        spa[Recruiter SPA + Candidate interview page]
    end

    subgraph backend[backend-classic - Spring Boot 3 / Java 21 :8000]
        api[REST API /api/**]
    end

    db[(PostgreSQL 16 :15433)]:::infra
    storage[[Local disk ./storage]]:::infra
    gemini{{Gemini API}}:::ext
    twilio{{Twilio}}:::ext

    admin --> spa
    emp --> spa
    cand -->|single-use link| spa
    spa -->|JWT / token| api
    api --> db
    api --> storage
    api -->|GeminiClient| gemini
    api -->|TwilioClient| twilio
    twilio -->|signed webhooks| api

    classDef person fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
    classDef ext fill:#fef3c7,stroke:#b45309,color:#7c2d12;
    classDef infra fill:#dcfce7,stroke:#15803d,color:#14532d;
```

---

## 2. Layered request path (the shape every feature follows)

```mermaid
flowchart TD
    req[HTTP request] --> filter

    subgraph filters[filter + security]
        filter[RequestIdFilter -> RateLimitFilter] --> jwt[JwtAuthFilter\nresolve token to User]
    end

    jwt --> ctrl[controller/\nvalidate @Valid DTO, call ONE service method]
    ctrl --> svc[service/ interface]
    svc --> impl[service/impl/\nBUSINESS LOGIC]
    impl --> repo[repository/\nSpring Data JPA]
    repo --> ent[entity/ @Entity]
    ent --> repo
    repo --> impl
    impl --> mapper[mapper/\nentity -> response DTO]
    mapper --> resp[DTO response]

    impl -.->|external calls only| client[client/\nGeminiClient / TwilioClient]
    impl -.->|helpers| util[util/\nTika, SkillDictionary, Crypto, FileStorage]
    impl -.->|throws ApiException| gh[GlobalExceptionHandler\n-> {ok:false, message, code}]

    classDef biz fill:#ede9fe,stroke:#6d28d9,color:#4c1d95;
    class impl,svc biz;
```

---

## 3. Modules (feature map)

```mermaid
flowchart TB
    subgraph app[Modular Monolith - com.smartstaff]
        auth[auth\nusers, roles, login, approval]
        jobs[jobs\nJDs, resumes, files]
        screening[screening\nparsing, deterministic scoring, AI chat]
        assessment[assessment\nquestions, bank, invites]
        interview[interview\nbrowser + phone, transcripts]
        activity[activity\nevent feed / bell]
        settings[settings\nadmin config: Gemini, Twilio]
    end

    jobs --> screening
    screening --> assessment
    assessment --> interview
    settings -.->|keys| screening
    settings -.->|keys| assessment
    settings -.->|keys| interview
    jobs -.->|events| activity
    screening -.->|events| activity
    assessment -.->|events| activity
    interview -.->|events| activity
```

---

## 4. End-to-end hiring flow (sequence)

```mermaid
sequenceDiagram
    actor HR
    participant FE as Frontend
    participant API as Backend
    participant AI as Gemini
    actor Cand as Candidate
    participant Tw as Twilio

    HR->>API: POST /api/upload_jd (PDF/DOCX)
    API-->>HR: JD-0001 + extracted skills
    HR->>API: POST /api/upload_resumes
    HR->>API: POST /api/run_screening
    Note over API: DETERMINISTIC score /100 (no AI)
    API-->>HR: ranked candidates (>=50 shortlisted)
    HR->>API: POST /api/interview/invites/mint
    API->>AI: generate 5 questions (JD + resume)
    AI-->>API: questions
    API-->>HR: single-use link (mailto: prepared)

    alt Browser interview
        Cand->>FE: open link, allow mic
        FE->>Cand: read question aloud
        Cand->>FE: speak answer (speech-to-text)
        FE->>API: POST /api/interview/save_by_token (consume token)
    else Phone interview
        HR->>API: POST /api/interview/place_call
        API->>Tw: dial candidate
        Tw->>Cand: call + AI voice asks
        Tw->>API: signed TwiML webhooks (answers)
    end

    API-->>HR: transcript on Candidates page
    HR->>HR: read & decide
```

---

## 5. Entity-Relationship diagram (data model)

```mermaid
erDiagram
    USERS ||--o{ JOBS : "uploaded_by"
    JOBS ||--o{ RESUMES : has
    JOBS ||--o{ CANDIDATES : has
    RESUMES ||--o| CANDIDATES : "scored as"
    JOBS ||--o{ ASSESSMENTS : has
    ASSESSMENTS ||--o{ ASSESSMENT_QUESTIONS : contains
    JOBS ||--o{ INTERVIEWS : has
    INTERVIEWS ||--o{ INTERVIEW_TURNS : "Q&A"
    JOBS ||--o{ INVITES : has
    INVITES |o--o| INTERVIEWS : "may open"
    QUESTION_BANK_UPLOADS ||--o{ QUESTION_BANK_ITEMS : contains
    JOBS |o--o{ QUESTION_BANK_ITEMS : "job_id nullable = shared"

    USERS {
        uuid id PK
        string role "ADMIN / USER"
        string email
        string employee_id
        string password_hash
        string status "PENDING/APPROVED/REJECTED/REVOKED"
        timestamp last_seen_at
    }
    JOBS {
        uuid id PK
        string display_no "JD-0001"
        string title
        string file_path
        text jd_text
        string content_hash "dedupe"
        uuid uploaded_by FK
    }
    RESUMES {
        uuid id PK
        uuid job_id FK
        string filename
        string file_path
        text extracted_text
    }
    CANDIDATES {
        uuid id PK
        uuid job_id FK
        uuid resume_id FK
        string name
        string email
        string phone
        int years_experience
        int fit_score
        jsonb score_breakdown
        jsonb matched_skills
        jsonb missing_skills
        string stage
    }
    ASSESSMENTS {
        uuid id PK
        uuid job_id FK
        string source "AI/MIX/CUSTOM"
        string status
    }
    ASSESSMENT_QUESTIONS {
        uuid id PK
        uuid assessment_id FK
        string level "L1/L2/L3"
        string type "MCQ/MSQ/CODING/DESCRIPTIVE"
        text prompt
        jsonb options
        jsonb answer
    }
    QUESTION_BANK_UPLOADS {
        uuid id PK
        string filename
        uuid uploaded_by FK
        int item_count
    }
    QUESTION_BANK_ITEMS {
        uuid id PK
        uuid upload_id FK
        uuid job_id FK "nullable = shared"
        string level
        string type
        text prompt
    }
    INVITES {
        uuid id PK
        string token_hash "hash only, single-use"
        string kind "ASSESSMENT/INTERVIEW"
        uuid job_id FK
        uuid candidate_id FK
        uuid interview_id FK "nullable"
        timestamp expires_at
        timestamp used_at
    }
    INTERVIEWS {
        uuid id PK
        uuid job_id FK
        uuid candidate_id FK
        string mode "SELF/BROWSER/PHONE"
        string language
        string status
        string twilio_call_sid
    }
    INTERVIEW_TURNS {
        uuid id PK
        uuid interview_id FK
        int seq
        string category
        string skill
        text question
        text answer
    }
    APP_SETTINGS {
        string key PK
        string value "encrypted if secret"
        uuid updated_by
    }
```
