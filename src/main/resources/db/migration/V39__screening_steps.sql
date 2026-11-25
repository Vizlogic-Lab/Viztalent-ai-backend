-- Two-step screening: step 1 experience gate, step 2 skills/AI, plus a recruiter override.
alter table candidates add column experience_status varchar(16);
alter table candidates add column override_passed boolean not null default false;
alter table candidates add column override_reason text;
alter table candidates add column override_by varchar(255);
alter table candidates add column override_at timestamptz;
