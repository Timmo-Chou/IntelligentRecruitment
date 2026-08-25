# AI 智能招聘第一阶段产品与架构基线

版本：V0.2  
状态：评审稿  
日期：2026-08-21

## 1. 基线目的

本基线用于指导第一阶段的产品设计、客户端开发、AI 招聘业务服务开发，以及后续与伙伴 AI Platform 的契约联调。

第一阶段分工：

- 我方负责：① Web 客户端、② AI 招聘业务服务。
- 伙伴负责：③ AI Platform / AI Gateway、④ 能力接入层、⑤ 能力供应方接入。
- 伙伴暂未进入开发，因此我方先按照合同优先方式定义能力、接口、Mock 和验收标准。

本基线不授权开发伙伴平台内部的 Agent Runtime、模型网关、MCP Client、Prompt/Skill Registry 或供应商适配器。

## 2. 产品目标

面向企业 HR 和招聘顾问，构建一个“对话式 AI + 结构化招聘结果”的企业招聘工作台。第一阶段验证以下核心价值：

```text
招聘需求
→ AI 生成 JD 草稿
→ 人工确认职位版本
→ 导入/选择候选人
→ 人工确认筛选方案和预算
→ AI 人岗匹配
→ 人工选择候选人
→ AI 生成针对性面试题
```

AI 负责生成、分析和建议，招聘人员负责确认业务记录和招聘决策。

## 3. 架构原则

1. 客户端只调用 AI 招聘业务服务，不直连 AI Platform。
2. AI 招聘业务服务是用户、Company/Workspace、职位、候选人、任务、结果和客户账单的事实源。
3. AI Platform 负责模型、Agent、Skill、Tool、供应商调用和供应商成本统计。
4. AI 返回必须结构化并经过契约校验，不能只有自然语言。
5. 长任务可恢复、可查询、可取消，并处理重复请求、重复回调和部分成功。
6. AI 供应商成本与面向客户的产品计费分离。
7. 候选人数据默认脱敏；个人 Workspace、同一企业内不同 Workspace 及不同企业之间均严格隔离，敏感操作有审计。
8. 第一阶段使用模块化单体和异步 Worker，不提前拆业务微服务。

## 4. 文档导航

| 文档 | 内容 | 主要评审方 |
|---|---|---|
| [01-mvp-scope.md](01-mvp-scope.md) | P0 范围、页面、优先级、验收边界 | 产品、研发 |
| [02-responsibility-boundaries.md](02-responsibility-boundaries.md) | 双方职责、数据归属、接口边界 | 双方技术负责人 |
| [03-core-business-flows.md](03-core-business-flows.md) | JD、简历、筛选、面试题、计费流程 | 产品、前后端 |
| [04-system-architecture.md](04-system-architecture.md) | 系统组件、调用关系、部署和可靠性 | 架构、后端、运维 |
| [05-data-model.md](05-data-model.md) | 核心实体、关系、版本和状态 | 后端、数据、产品 |
| [06-ai-platform-contract-draft.md](06-ai-platform-contract-draft.md) | AI 能力、任务、回调、错误、Usage | 双方研发 |
| [07-quality-and-acceptance.md](07-quality-and-acceptance.md) | 安全、质量、测试和阶段验收 | 产品、研发、测试 |
| [08-open-decisions.md](08-open-decisions.md) | 必须确认、可延后、默认假设 | 决策人 |
| [09-development-phases.md](09-development-phases.md) | Phase 3—8 修订设计、依赖、数据 Scope、开发门禁和完成标准 | 产品、研发、测试 |
| [../architecture/technical-stack.md](../architecture/technical-stack.md) | 技术栈及不采用项 | 技术负责人 |

## 5. 需求来源和解释规则

基线依据《产品功能设计+UI视觉初稿（MVP）》和《AI招聘视觉规范skill.md》整理，并吸收前期架构讨论形成的修订建议。

解释优先级：

1. 用户后续明确确认的决策。
2. 本基线中标记为“已确定”的规则。
3. 原始产品文档中没有冲突的内容。
4. Skill 中的工程约束。
5. 尚未确定的内容必须进入待确认事项，不得由开发人员自行决定。

## 6. 基线变更规则

- 产品范围、计费、隐私、数据归属和 AI 契约变化必须形成决策记录。
- 接口不兼容变化必须提升契约版本。
- 已被筛选结果引用的 JD、简历解析和筛选方案不能原地覆盖。
- P1/P2 功能不得因为页面占位或技术便利无意进入 P0。
