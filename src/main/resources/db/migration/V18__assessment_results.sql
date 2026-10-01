-- F10: dual scoring results. theory/hands_on/overall_score and recommendation
-- already exist (V16); this adds the bands, per-competency sub-scores, the
-- evidence lines and the short summary shown to HR. jobs.scoring_settings holds
-- the per-job weights and gate thresholds (NULL = the defaults in ScoringSettings).

ALTER TABLE jobs ADD COLUMN scoring_settings JSONB;

ALTER TABLE assessment_attempts
    ADD COLUMN theory_band       VARCHAR(20),
    ADD COLUMN hands_on_band     VARCHAR(20),
    ADD COLUMN competency_scores JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN evidence_lines    JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN summary           JSONB;

ALTER TABLE assessment_attempts ADD CONSTRAINT ck_assessment_attempts_recommendation
    CHECK (recommendation IS NULL OR recommendation IN ('PROCEED', 'HR_REVIEW', 'NOT_RECOMMENDED'));
