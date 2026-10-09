# RD-001 Phase 0：契约与开发基线

日期：2026-10-07。状态：Phase 0 契约基线；业务运行时尚未实现。

本包是《Recruitment Distribution对接方案.md》Phase 0 的实施产物。主方案和实际代码优先；外部 RD 机器契约唯一来源是锁定的 v1.1.0 OpenAPI。包内 JSON Schema 与语义规则共同构成本方内部 v2 实施合同，不代表现有服务已支持。旧 Grant v1 文件保留为当前运行基线，不覆盖、不构建双协议回退。

## 文件与使用

- `baseline-matrix.json`：已 fetch 的三仓库 SHA、功能分支及迁移文件 SHA-256。部署版本、实际数据库 Flyway 状态、凭据和价格版本未核验，明确为 null。
- `contracts/internal-v2.schema.json`：授权、Grant、执行、查询、事件、结果、结算和文件桥接 Schema。按 `$defs` 名称选取入口。
- `contracts/routes.json`、`contracts/states.json`：跨仓库共享能力与状态矩阵。
- `CONTRACT.md`：字段语义、接口、约束、哈希和错误处理的规范性补充。
- `IMPLEMENTATION.md`：P0/P1 划分、三仓库任务和验收依赖。
- `external/`：OpenAPI 与交付包 05/06 的原样快照。
- `fixtures/`：交付包合成响应、本方示例 Grant claims、统一结果和哈希向量。无真实 JWS、Key、简历或业务数据。
- `scripts/fake_rd.py`：仅监听 127.0.0.1 的合成 HTTP 服务，覆盖 readiness 和三个 Direct 接口，不调用供应商。
- `scripts/verify_phase0.py`：离线契约与本地 HTTP 校验。
- `manifest.json`：除 manifest 自身及 Python 缓存以外的文件 SHA-256，便于校验三仓库副本一致。

在独立 Python 环境安装 `requirements.txt` 后运行：

```sh
python scripts/verify_phase0.py
python scripts/fake_rd.py --port 18081
```

Fake 使用测试常量 `Authorization: Bearer phase0-fake-key`，不读取真实凭据。multipart 测试内容只是合成字节；Fake 不验证 PDF/DOCX 文档内部格式，不实现真实模型、计费、Worker 或状态生命周期。其契约覆盖不等于真实 RD、Java Adapter 或端到端联调已通过。

本工作区总包是本阶段编辑源；三个仓库 `docs/rd-001-phase0/` 保存同一完整副本供独立开发/审查。后续修改必须同步所有副本并更新 manifest；发布矩阵在三仓库代码提交与部署时填入实际版本。本阶段不提交、不推送，也不运行数据库迁移。
