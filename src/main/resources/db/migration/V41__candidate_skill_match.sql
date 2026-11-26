-- Per-skill AI check: for each JD skill, STRONG / PARTIAL / MISSING with a quote from the CV.
-- fit_score now holds the skill match % (AI when available, else the keyword check);
-- skill_match_source says which one: AI or KEYWORD.
alter table candidates add column skill_match_source varchar(16);
alter table candidates add column skill_results jsonb;
