# 数据库迁移与基线工作流

## 当前基线

`services/recruitment-service/src/main/resources/db/migration/V1__baseline.sql` 是 2026-09-03 收敛后的完整数据库结构和系统初始化数据基线。它替代了此前的 `V1` 至 `V31` 开发期迁移链。

新建空数据库时，Flyway 只需执行该基线及其之后的增量迁移。

## 新迁移命名

从本基线之后，迁移统一使用毫秒级时间戳版本：

```text
V20260903170000123__add_candidate_source_channel.sql
```

格式：`VyyyyMMddHHmmssSSS__lower_snake_case.sql`。

- 不再人工维护 `V32`、`V33` 等连续编号。
- 提交前确认版本号在当前分支中唯一。
- 一个独立的数据结构变更对应一个迁移文件。
- 已合并到共享分支、测试环境或生产环境的迁移不得修改；通过新的向前迁移修复。

## 本地开发库的本次过渡

已有本地库已经执行了旧迁移链时，可保留数据并一次性标记为版本 `1` 基线；该操作仅用于本次收敛，不要作为日常流程。

```bash
# 先确保旧库已具备与 V1__baseline.sql 相同的结构。
# 删除旧的 Flyway 历史表后，以 V1 作为基线标记启动一次服务。
# 仅清空表中的记录会被 Flyway 视为“空 schema”，并尝试重放 V1。
docker compose -f infra/compose.yaml exec -T postgres \
  psql -U recruitment -d intelligent_recruitment -c 'DROP TABLE flyway_schema_history;'

SPRING_FLYWAY_BASELINE_ON_MIGRATE=true \
SPRING_FLYWAY_BASELINE_VERSION=1 \
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

若本地数据无需保留，推荐删除并重建本地数据库后直接启动服务，让 Flyway 执行 `V1__baseline.sql`。不要在共享、测试或生产数据库中执行上述 `DROP TABLE`。

## 基线再收敛

仅在进入测试/生产前或一个明确的开发阶段结束时进行：先在干净数据库验证现有完整迁移链，再生成并验证新的单文件基线；随后由全体开发者重建本地库或按统一计划过渡。不要临时删除某几条历史迁移。
