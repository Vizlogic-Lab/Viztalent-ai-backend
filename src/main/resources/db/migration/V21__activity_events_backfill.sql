-- F14: the feed now reads activity_events only. Backfill the entries the old
-- feed derived from jobs, resumes and candidates, with the same messages and
-- timestamps, so existing jobs keep their history. New rows come from the
-- services (one event per upload batch / screening run).
INSERT INTO activity_events (job_id, kind, message, subject_id, at)
SELECT id, 'jd', 'JD uploaded: ' || title, id, created_at
FROM jobs;

INSERT INTO activity_events (job_id, kind, message, subject_id, at)
SELECT job_id, 'resumes', 'Resume uploaded: ' || filename, NULL, uploaded_at
FROM resumes;

INSERT INTO activity_events (job_id, kind, message, subject_id, at)
SELECT job_id, 'screening',
       'Screened ' || COALESCE(candidate_name, 'candidate') || ' — fit ' || fit_score || '/100', NULL, created_at
FROM candidates;
