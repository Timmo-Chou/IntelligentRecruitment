-- V27: RESUME_PARSING 人才库关联 — recruitment_tasks.linked_candidate_id + 列表/详情查询
-- 场景：用户在首页空态点"选择人才（人才库）"后创建解析任务，后端保存关联并在无上传文件时
--      直接从人才库 candidates 表提取简历文本注入 payload.resumes。

-- 1. 任务关联人才库候选人（候选人被删自动置空）
ALTER TABLE recruitment_tasks
    ADD COLUMN linked_candidate_id UUID REFERENCES candidates(id) ON DELETE SET NULL;

CREATE INDEX idx_recruitment_tasks_linked_candidate
    ON recruitment_tasks (workspace_id, linked_candidate_id, updated_at DESC)
    WHERE linked_candidate_id IS NOT NULL;

-- 2. 工作空间/公司数据范围触发器（与 V23 的 scope 保持一致）
CREATE OR REPLACE FUNCTION fn_enforce_recruitment_task_linked_candidate_scope() RETURNS trigger AS $$
BEGIN
    IF NEW.linked_candidate_id IS NOT NULL THEN
        NEW.company_id := COALESCE(NEW.company_id, (SELECT company_id FROM workspaces WHERE id = NEW.workspace_id));
        IF EXISTS (
            SELECT 1 FROM candidates c
            WHERE c.id = NEW.linked_candidate_id
              AND (c.workspace_id <> NEW.workspace_id OR c.company_id IS DISTINCT FROM NEW.company_id)
        ) THEN
            RAISE EXCEPTION 'Linked candidate % does not belong to workspace %', NEW.linked_candidate_id, NEW.workspace_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_recruitment_tasks_linked_candidate_scope ON recruitment_tasks;
CREATE TRIGGER trg_recruitment_tasks_linked_candidate_scope
BEFORE INSERT OR UPDATE OF linked_candidate_id, workspace_id, company_id ON recruitment_tasks
FOR EACH ROW EXECUTE FUNCTION fn_enforce_recruitment_task_linked_candidate_scope();
