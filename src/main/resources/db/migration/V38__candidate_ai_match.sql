-- Meaning-based (AI) CV vs JD match, shown next to the keyword fit score.
alter table candidates add column ai_match_score integer;
alter table candidates add column ai_match_status varchar(16);
alter table candidates add column ai_match_summary text;
alter table candidates add column ai_match_strengths jsonb;
alter table candidates add column ai_match_gaps jsonb;
