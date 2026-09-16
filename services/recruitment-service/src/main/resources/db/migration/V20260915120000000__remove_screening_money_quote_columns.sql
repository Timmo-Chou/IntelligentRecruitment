-- AI credit authorization and consumption are owned by BOSS.  Recruitment must
-- not persist a second local price, quote, reservation, or settlement ledger.
ALTER TABLE screening_runs
    ALTER COLUMN pricing_version DROP NOT NULL,
    ALTER COLUMN unit_price_minor DROP NOT NULL,
    ALTER COLUMN estimated_amount_minor DROP NOT NULL,
    ALTER COLUMN settled_amount_minor DROP NOT NULL;

ALTER TABLE screening_runs
    DROP CONSTRAINT IF EXISTS screening_runs_quote_id_fkey;
DROP INDEX IF EXISTS uk_screening_run_quote;
ALTER TABLE screening_runs
    DROP COLUMN IF EXISTS quote_id,
    DROP COLUMN IF EXISTS pricing_version,
    DROP COLUMN IF EXISTS unit_price_minor,
    DROP COLUMN IF EXISTS estimated_amount_minor,
    DROP COLUMN IF EXISTS settled_amount_minor;

DROP TABLE IF EXISTS screening_quotes;

ALTER TABLE ai_runs
    ALTER COLUMN pricing_version DROP NOT NULL,
    ALTER COLUMN estimated_amount_minor DROP NOT NULL,
    ALTER COLUMN settled_amount_minor DROP NOT NULL;
ALTER TABLE ai_runs
    DROP COLUMN IF EXISTS pricing_version,
    DROP COLUMN IF EXISTS estimated_amount_minor,
    DROP COLUMN IF EXISTS settled_amount_minor;
