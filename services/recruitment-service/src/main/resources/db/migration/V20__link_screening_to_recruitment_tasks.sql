-- 筛选方案与执行记录是唯一的筛选事实来源；招聘任务仅作为其业务上下文。
ALTER TABLE screening_plans
    ADD COLUMN recruitment_task_id UUID REFERENCES recruitment_tasks(id);

ALTER TABLE screening_runs
    ADD COLUMN recruitment_task_id UUID REFERENCES recruitment_tasks(id);

CREATE INDEX idx_screening_plans_recruitment_task
    ON screening_plans (recruitment_task_id, updated_at DESC)
    WHERE recruitment_task_id IS NOT NULL;

CREATE INDEX idx_screening_runs_recruitment_task
    ON screening_runs (recruitment_task_id, created_at DESC)
    WHERE recruitment_task_id IS NOT NULL;

-- V18 的任务级配置未参与实际的 ScreeningPlan / ScreeningRun 执行，现移除以避免双数据源。
ALTER TABLE recruitment_tasks
    DROP COLUMN IF EXISTS screening_dims_json;
