ALTER TABLE resume_parse_versions
    ALTER COLUMN years_experience DROP NOT NULL,
    ALTER COLUMN years_experience DROP DEFAULT,
    ALTER COLUMN years_experience TYPE NUMERIC(5,2) USING years_experience::NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS structured_data JSONB;

UPDATE candidates
SET profile=jsonb_set(profile,'{yearsExperience}',to_jsonb(NULLIF(profile->>'yearsExperience','')::NUMERIC(5,2)),true)
WHERE profile ? 'yearsExperience' AND NULLIF(profile->>'yearsExperience','') IS NOT NULL;
