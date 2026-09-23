# AI 闭环 Phase 0 契约冻结

版本：V1.0  
状态：开发环境实施基线  
范围：BOSS、IntelligentRecruitment 用户端与 Admin、AIAgentPlatform、ApiGateway

## 1. 冻结结论

1. BOSS 是 Tenant、产品域、套餐、权益、积分预占、实扣和释放的唯一权威。
2. IntelligentRecruitment 是招聘业务编排方，负责先向 BOSS 请求授权，再调用 AIAgentPlatform。
3. AIAgentPlatform 是执行方，只接受 IR 服务身份和 BOSS 签发的 AI Grant，不调用 BOSS。
4. usage report 和 execution cancellation 统一由 IR 调用 BOSS。
5. 不提供独立的 `/ai/v1/**` 开放面；外部系统不能绕过招聘业务和 BOSS 授权直接调用 Agent。
6. Admin 是本次闭环的一部分：Bootstrap 仅使用 `X-Platform-Admin-Key`，其他平台接口仅使用管理员 Bearer Token。
7. 本轮不真实调用 DeepSeek；模型、Tokenizer 和实际用量字段仍必须按正式契约记录。

## 2. Grant 签名

- Grant 由 BOSS 签发，IR 只转发，Agent 验签。
- `X-AI-Grant` 只传递原始紧凑 JWS；IR 服务 Bearer Token 单独放在 `Authorization`。
- 签名格式：紧凑 JWS，算法 `RS256`。
- JWS Header 必须包含 `alg=RS256` 和 `kid`；Agent 根据配置的 BOSS 公钥/JWK 集合选择公钥。
- Grant 不携带私钥、BOSS client secret 或积分余额。
- `iss`、`aud`、`exp`、`nbf`、`iat` 必须校验；时钟偏差由服务端统一容忍窗口处理。
- Grant 过期只禁止 Agent 接受新执行，不阻止 IR 使用原 `reservation_id` 完成结算或释放。

完整字段定义见 `contracts/ai-platform/schemas/ai-grant.schema.json`。

## 3. 状态冻结

Grant 状态：

```text
ISSUED -> ACCEPTED -> CONSUMED
                  -> REJECTED
                  -> EXPIRED
```

Reservation 状态：

```text
RESERVED -> EXECUTING -> CAPTURED
                      -> RELEASED
                      -> CANCELLED
```

所有 usage、cancellation 和重试操作必须使用原授权记录和幂等键，不能创建新的本地积分预占记录。

## 4. 服务认证

- IR -> BOSS：BOSS 内部服务凭证。
- IR -> Agent：IR 专用服务凭证 + AI Grant。
- Agent -> BOSS：禁止建立调用链和结算客户端。
- 浏览器：只持有用户或管理员 Bearer Token，不持有服务凭证或 Grant 签发凭证。

## 5. 产品域

授权、套餐、权益和计费请求必须明确产品域：

- `RECRUITMENT`：招聘用户端套餐和 AI 能力；
- `OPEN_API`：Open API 客户端和 API Key 业务。

两个产品域不得共享套餐、权益、积分规则或 API 客户端上下文。

## 6. 旧链路清理

- 删除 Agent 和 ApiGateway 的 `/ai/v1/**` 路由、契约定义和旧过滤器分支。
- 删除 `AI_AGENT_ALLOW_LEGACY_ENDPOINTS` 配置。
- 删除普通平台接口使用 `X-Platform-Admin-Key` 的认证回退。
- 删除平台管理员 API Key 生成、保存和返回逻辑；OpenAPI 的 `api_keys` 不属于本次删除范围。

Agent 内部的 BOSS 授权客户端和旧运行时结算调用已在阶段 1 清理；Agent 运行时只保留本地 Grant 验签和任务执行，结算由 IR 的持久化 Outbox 投递至 BOSS。

## 7. Phase 0 验收

- Agent 不再暴露 `/ai/v1/**` 映射；Gateway 不再转发该路径。
- AI Contract 不再定义独立开放面。
- Admin 普通接口没有 API Key 回退认证。
- Bootstrap 仍可使用专用引导密钥。
- OpenAPI `/open/v1/**` 和 `api_keys` 仍保持原有功能。
- 旧路径请求得到 404，而不是被转发到 Agent 或计费链路。
