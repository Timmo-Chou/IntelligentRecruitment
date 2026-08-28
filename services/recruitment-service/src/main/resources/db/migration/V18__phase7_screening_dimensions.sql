-- Phase 7：简历筛选六维评估维度保存到 recruitment_tasks
ALTER TABLE recruitment_tasks ADD COLUMN screening_dims_json JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN recruitment_tasks.screening_dims_json IS '简历筛选六维评估维度配置：[{id,name,weight,description},...]。六列默认与前端一致，支持工作空间内自定义权重与说明。';

CREATE TRIGGER trg_recruitment_tasks_dims_scope BEFORE INSERT OR UPDATE ON recruitment_tasks
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
