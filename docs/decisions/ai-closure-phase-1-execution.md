# AI 闭环阶段 1：Grant 执行与结算

阶段 1 将三方职责落实为：IR 先调用 BOSS `execution-authorizations`；BOSS 校验 Tenant 权益、预占积分并使用 RS256 签发短时 Grant；IR 使用专用 `AI_AGENT_IR_SERVICE_TOKEN` 携带 `X-AI-Grant` 调用 AIAgentPlatform。Agent 只校验服务凭证和 Grant，不访问 BOSS。

任务完成后由 IR 调用 BOSS `usage-reports`，失败或取消调用 `execution-cancellations`。用量上报必须包含 authorization_id、reservation_id、幂等键、实际模型、输入/输出 Token、重试次数和结果引用。模型与 Tokenizer 由 BOSS 运营配置的 `ai_feature_rules.model_id/tokenizer_id` 提供，禁止在 Agent 或 IR 硬编码模型标识。

部署时 BOSS 注入 Grant 私钥（`BOSS_AI_GRANT_PRIVATE_KEY_PEM`），并将 BOSS `/internal/ai/grants/jwks` 返回的公钥集合注入 Agent `AI_AGENT_BOSS_GRANT_JWKS_JSON`。IR 与 Agent 共享独立的 `AI_AGENT_IR_SERVICE_TOKEN`。本阶段不调用真实 DeepSeek；验收使用已配置的模型规则和契约测试。

## 阶段 2 实施边界

IR 的 `ai_execution_records` 保存授权、Agent 任务、模型、Tokenizer、重试次数和结果引用；`ai_settlement_outbox` 持久化 USAGE/CANCELLATION 投递意图。IR 只在本地事务中写入 Outbox，由 `AiSettlementOutboxWorker` 调用 BOSS 正式 `usage-reports` 或 `execution-cancellations`，成功后标记完成，失败按指数退避重试，达到上限后保留失败状态供 BOSS 预占生命周期对账。

AIAgentPlatform 不再拥有 BOSS 授权客户端、结算客户端或用量副作用；它只校验 IR 服务凭证和原始 `X-AI-Grant`，并把 Grant 中的模型和 Tokenizer 固化到任务后再执行。任务幂等键按 `(tenant_id, capability, idempotency_key)` 唯一，避免不同 Tenant 互相命中。
