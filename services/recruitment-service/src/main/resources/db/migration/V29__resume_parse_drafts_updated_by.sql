-- =====================================================================
-- 修复简历解析草稿保存草稿报错：
--   RecruitmentService.updateResumeParseDraft() 的 UPDATE 语句引用了 updated_by 列，
--   但 V23__resume_parsing.sql 建表时遗漏了该列，导致 PostgreSQL 抛出
--   "column updated_by of relation resume_parse_drafts does not exist"。
-- =====================================================================

-- 1. 给 resume_parse_drafts 增加 updated_by 列（与 created_by 保持一致：可空 + FK users(id)）
ALTER TABLE resume_parse_drafts
    ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES users(id);

-- 2. 回填已有数据的 updated_by = created_by（存量草稿默认创建者即最后修改者）
UPDATE resume_parse_drafts
   SET updated_by = created_by
 WHERE updated_by IS NULL;

-- 3. 注释说明
COMMENT ON COLUMN resume_parse_drafts.updated_by IS '最后一次更新该草稿版本的用户ID（手动保存/AI 回写时记录操作人，用于审计与版本对比）';
