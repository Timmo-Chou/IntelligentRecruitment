-- 为 companies 表增加营业执照引用字段，并回填历史申请材料。
-- 原拆分为两个独立迁移（加列 + 回填），现将其合并为单一迁移，保证新建库时一个版本号即可落地。
-- 说明：
--   1) 使用 IF NOT EXISTS 确保重复执行安全（例如 Flyway repair 后重新跑本迁移时，列已存在不会报错）；
--   2) UPDATE 仅覆盖 companies.license_reference 为空 / 空串的记录，因此重复执行也是幂等（已回填的数据不会被覆盖）。

ALTER TABLE companies ADD COLUMN IF NOT EXISTS license_reference VARCHAR(500);

COMMENT ON COLUMN companies.license_reference IS '营业执照文件引用（存储为对象存储 objectKey，兼容旧数据纯文件名）';

UPDATE companies c
SET license_reference = sub.license_reference
FROM (
    SELECT DISTINCT ON (cvr.company_id)
           cvr.company_id, cvr.license_reference
      FROM company_verification_requests cvr
     WHERE cvr.company_id IS NOT NULL
       AND cvr.status = 'APPROVED'
     ORDER BY cvr.company_id,
              cvr.reviewed_at DESC NULLS LAST,
              cvr.created_at DESC
) sub
WHERE c.id = sub.company_id
  AND (c.license_reference IS NULL OR c.license_reference = '');
