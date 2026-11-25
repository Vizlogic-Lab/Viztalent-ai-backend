-- JD skills read by AI: other ways a CV may phrase each skill, and where the skill list came from.
alter table jobs add column skill_aliases jsonb;
alter table jobs add column skills_source varchar(16);
