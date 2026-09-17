# AI 智能招聘项目文档

## 第一阶段入口

- [产品与架构基线](baseline/README.md)
- [第一阶段技术栈设计](architecture/technical-stack.md)
- [Phase 2 身份、租户、权限与数据隔离方案](architecture/phase-2-identity-tenancy-and-isolation.md)
- [Recruitment SaaS 对接 BOSS 实施契约](architecture/recruitment-saas-boss-integration.md)
- [Agent 编排契约 V1](architecture/agent-orchestration-contract-v1.md)
- [AI 运行现状与 Mock 退役方案](architecture/ai-runtime-and-mock-retirement.md)
- [业务应用前端与服务端开发阶段（Phase 3—8 已按最新 Phase 2 修订）](baseline/09-development-phases.md)
- [Phase 0 可开发基线冻结记录](decisions/phase-0-development-freeze.md)
- [Phase 0 / Phase 1 完成记录](decisions/phase-1-completion.md)
- [Phase 2 业务决策记录](decisions/phase-2-business-decisions.md)

## 核心 Skill

- [产品 Skill](../skills/ai-recruitment-product/SKILL.md)
- [前端 Skill](../skills/ai-recruitment-frontend/SKILL.md)
- [后端 Skill](../skills/ai-recruitment-backend/SKILL.md)
- [AI Platform 集成 Skill](../skills/ai-platform-integration/SKILL.md)
- [质量 Skill](../skills/ai-recruitment-quality/SKILL.md)

## 当前状态

- Phase 0、Phase 1 已完成，当前基线以 BOSS 为身份、产品租户、成员席位、权限、套餐、积分和账单权威。
- Recruitment SaaS 仅保留招聘业务数据；对外上下文使用 BOSS Recruitment Tenant ID。企业 Tenant 直接对应企业，不存在 Company 中间层；开发环境无历史数据迁移，数据库基线可直接替换。
- Phase 3、Phase 4、Phase 5 已形成可运行的 MVP 代码基线；Phase 6—8 计划已按最新租户、数据隔离和账本规则修订。
- AI Platform Contract V1 已形成文档基线；招聘服务通过 BOSS 授权调用 AIAgentPlatform，模型执行与用量结算边界见 [AI 运行现状与 Mock 退役方案](architecture/ai-runtime-and-mock-retirement.md)。
