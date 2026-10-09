ALTER TABLE jd_drafts
    ADD COLUMN IF NOT EXISTS jd_text TEXT NOT NULL DEFAULT '';

UPDATE jd_drafts
SET jd_text = concat(
    '职位名称：', title,
    E'\n企业名称：', company_name,
    E'\n工作地点：', location,
    E'\n薪资范围：', salary_range,
    E'\n经验要求：', experience_level,
    E'\n学历要求：', education,
    E'\n用工类型：', job_type,
    E'\n\n岗位职责\n', responsibilities,
    E'\n\n任职要求\n', requirements,
    E'\n\n关键技能\n', skills,
    E'\n\n加分项\n', nice_to_haves,
    E'\n\n福利待遇\n', benefits,
    E'\n\n人才画像\n', talent_profile
)
WHERE jd_text = '';
