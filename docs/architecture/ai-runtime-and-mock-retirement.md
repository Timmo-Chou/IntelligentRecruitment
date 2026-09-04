# AI 运行现状与 Mock 退役方案

状态：**已完成运行时 Mock 退役**
更新时间：2026-09-03

## 运行时边界

所有 `AiPlatformClient` 业务能力统一由 `DeepSeekAiPlatformClient` 实现，不再存在 `mock` 模式、`MockAiPlatformClient` 或运行时模拟场景。

本地开发需配置：

```text
DEEPSEEK_API_KEY=由本地环境注入
DEEPSEEK_ALLOW_EXTERNAL_DATA=true
```

密钥不得提交到仓库、前端、日志或任务输入。`DEEPSEEK_ALLOW_EXTERNAL_DATA=false` 会拒绝发送 JD、简历或人才数据。

## 能力处理语义

| 功能 | 正常路径 | 失败处理 |
|---|---|---|
| JD 生成 | DeepSeek JSON/流式结果 | AI run 失败，可重试，不保存本地 JD。 |
| 智能招聘简历解析 | DeepSeek JSON Markdown | AI run 失败，可重试，不保存本地摘要。 |
| 人才库上传与重试解析 | 上传后调用 DeepSeek，成功写入解析版本 | 保留候选人与原文件，状态为 `PARSE_FAILED`，可重试。 |
| 简历筛选 | DeepSeek JSON | 单个候选人失败；只对成功项目结算。 |
| 面试出题 | DeepSeek JSON | 不创建题包，调用方将本次任务标记失败。 |
| 招聘对话、意图路由、JD 改写、咨询助手 | DeepSeek | 返回明确的模型不可用错误，不使用本地话术伪装成功。 |

`scenario` 不再暴露在 JD、人才解析和筛选 API 或前端。契约测试可以使用 HTTP Stub，但测试替身不得进入生产运行时路径。

## 保留的非 AI 本地能力

- 单元测试中的 `vi.mock`、Mockito、HTTP Stub 用于隔离测试，不属于运行时 AI Mock。
- 手机验证码本地通道不属于 AI 平台，需按认证上线计划单独替换。
- 筛选规则类可用于输入校验或测试，但不再生成或保存 `RULE_FALLBACK` 筛选结果。

## 本地开发限制

当前 DeepSeek 适配器以进程内任务表跟踪异步调用；服务重启期间正在执行的请求需要由用户重试。进入生产前应将供应商任务状态、重试、用量、延迟与成本持久化，并补充限流、超时和告警。

简历、JD 和人才档案可能包含个人信息。即使仅在本地开发，也应只使用获授权的测试数据，并在使用真实数据前完成数据处理告知、最小化传输、供应商协议和访问审计。
