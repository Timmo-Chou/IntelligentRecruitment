-- The initial v2 deployment starts drained; root acceptance opens only after controlled validation.
UPDATE recruitment_execution_maintenance SET state='DRAINING',protocol_upgrade=TRUE
WHERE id=1 AND NOT EXISTS(SELECT 1 FROM recruitment_maintenance_audits);
