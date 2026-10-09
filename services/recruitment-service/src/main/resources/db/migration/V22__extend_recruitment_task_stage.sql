-- Stage values include user-facing workflow milestones and may be longer than
-- the original 32-character column, e.g. AWAITING_INTERVIEW_KIT_CONFIRMATION.
ALTER TABLE recruitment_tasks
    ALTER COLUMN current_stage TYPE character varying(64);
