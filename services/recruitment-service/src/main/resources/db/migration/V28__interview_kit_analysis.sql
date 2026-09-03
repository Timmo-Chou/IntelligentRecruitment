ALTER TABLE interview_kits
    ADD COLUMN core_competencies JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN match_summary TEXT NOT NULL DEFAULT '';

ALTER TABLE interview_questions
    ADD COLUMN reference_answer_points TEXT NOT NULL DEFAULT '';
