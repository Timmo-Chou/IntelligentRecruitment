# AI 运行现状与 Mock 退役方案

状态：**Current implementation snapshot**  
更新时间：2026-09-03  
范围：招聘业务服务中 `AiPlatformClient`、DeepSeek 适配器、规则兜底和本地模拟场景。

## 1. 运行时选择

业务服务通过 `AiPlatformClient` 使用 AI 能力，并按 `app.ai-platform.mode` 注入一个实现：

| 配置 | 实现 | 行为 |
|---|---|---|
| `mock` | `MockAiPlatformClient` | 进程内立即完成的确定性结果，不调用外部模型。 |
| `deepseek` | `DeepSeekAiPlatformClient` | 调用 DeepSeek `/chat/completions`，部分能力仍保留本地兜底。 |

`application.yml` 的默认模式为 `deepseek`，而 `.env.example` 将其覆盖为 `mock`，因此按本地示例启动时会使用 Mock。DeepSeek 调用还必须同时满足：

```text
DEEPSEEK_API_KEY=由运行环境注入
DEEPSEEK_ALLOW_EXTERNAL_DATA=true
```

`DEEPSEEK_ALLOW_EXTERNAL_DATA=false` 时，适配器拒绝发送任何外部请求。API Key 不得写入仓库、浏览器、任务输入或日志。

## 2. 各能力的真实路径

| 功能 | DeepSeek 正常路径 | 当前兜底/模拟行为 |
|---|---|---|
| 招聘意图路由 | DeepSeek JSON 路由 | `mock` 模式使用关键词意图目录；DeepSeek 路由失败没有平台级兜底。 |
| 招聘对话、JD 就地改写 | DeepSeek | `mock` 模式分别返回固定回复、原 JD；业务服务会保存用户消息并返回“稍后重试”提示。 |
| JD 生成 | 异步流式 DeepSeek JSON | `mock` 模式拼装固定字段；DeepSeek 失败时 JD run 失败并可重试，不回退 Mock。 |
| 智能招聘内简历解析 | DeepSeek JSON Markdown | DeepSeek 已开始任务后发生异常或结构不合法时，回退 `mockResumeStructuredResult`。 |
| AI 简历筛选 | DeepSeek JSON | 平台调用、轮询或结果失败时，使用 `ScreeningMatcher` 规则结果并标记 `RULE_FALLBACK`。 |
| AI 面试出题 | DeepSeek JSON | DeepSeek 客户端异常时回退 Mock；`InterviewService` 捕获异常后还会再回退本地模板。 |
| 人才库上传后的基础解析 | 不调用 AI Platform | 始终使用文件名、正则和内置技能词表解析，摘要标记为“Mock 解析结果”。 |
| AI 咨询助手 | 独立直接调用 DeepSeek | 没有 Key、关闭 LLM 或请求失败时使用硬编码客服话术；它不走 `AiPlatformClient`。 |

上述“兜底”与正常结果的语义不同：JD 失败会显式失败；筛选、解析和面试题在部分故障下仍可能给用户返回可编辑或可结算的本地结果。

## 3. Mock 具体覆盖范围

`MockAiPlatformClient` 提供以下确定性行为：

- 在内存中保存任务、幂等键和 `StructuredResult`，任务创建后即 100% 完成。
- 根据关键词目录完成路由；根据输入字段拼装 JD、候选人筛选分数和说明。
- 对简历文本进行启发式摘要，生成 Markdown。
- 根据 JD 与人才技能生成 3 项胜任力、匹配度总结、四类模板面试题、参考答案与评分标准。
- 对话仅回显最新用户补充；JD 改写不执行真实改写。

此外，`scenario` 仍可人为制造异常：JD 与人才上传解析支持 `TIMEOUT` / `INVALID_SCHEMA`，筛选额外支持 `PARTIAL_FAILURE`。前端仅在 `NEXT_PUBLIC_SHOW_AI_MOCK_SCENARIOS=true` 时展示筛选场景选择器，但 API 仍接受这些参数。

## 4. 全量切换到 DeepSeek 的目标状态

“移除 Mock”应定义为：业务结果只来自 DeepSeek 的通过 Schema 校验的输出；模型不可用、超时或输出不合约时记录失败并允许重试，**不得保存为成功的本地模板结果**。

目标处理流程：

```text
冻结业务输入与数据处理授权
  → DeepSeek 调用
  → 专属 JSON Schema 校验
  → 有限次数修复/重试
  → 成功：持久化、结算
  → 失败：标记失败、释放未结算金额、展示重试
```

## 5. 必要改造

1. 删除 `MockAiPlatformClient` 运行时模式，以及 DeepSeek 代码中直接实例化 Mock 的回退调用。
2. 删除面试题服务的本地模板回退；模型失败时返回明确错误，不创建题包。
3. 将筛选 `RULE_FALLBACK` 改为候选人项失败、可逐项重试；结算仅针对 DeepSeek 成功且通过校验的结果。
4. 将人才库上传解析改造成 AI Platform 的异步能力，否则人才库和智能招聘解析会长期存在两套质量标准。
5. 移除生产 API 中的 `scenario` 参数和前端模拟开关；故障测试改为 HTTP stub/契约测试，不影响真实业务结果。
6. 为 JD、解析、筛选、面试题建立并强制执行能力专属 JSON Schema、模型输出修复重试、错误码和审计字段。
7. 统一 AI 咨询助手的治理方式：接入同一平台适配器，或明确其为非 AI 的规则客服流程。
8. 更新报价版本名（例如 `*_MOCK_V1`）、监控与对账：记录模型、Prompt 版本、token、延迟、重试和供应商成本，但不记录完整 PII Prompt。

## 6. 数据与合规前置条件

简历解析、筛选和面试题将向 DeepSeek 传递 JD、简历文本及人才档案摘要；其中可能包含姓名、联系方式、工作经历和教育经历。全量切换前必须完成：

- 数据处理授权、供应商 DPA/地域与保留期限确认；
- 传输字段最小化与 PII 脱敏策略；
- 用户告知与授权记录；
- 密钥管理、访问审计、超时与限流策略；
- 输出质量、偏差与人工复核机制。

在上述条件未满足前，应保留 `DEEPSEEK_ALLOW_EXTERNAL_DATA=false`，避免向外部模型发送候选人数据。
