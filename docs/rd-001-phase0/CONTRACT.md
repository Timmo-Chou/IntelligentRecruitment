# RD-001 内部 v2 合同

本文件与 `internal-v2.schema.json`、routes/states JSON 共同冻结本期合同。JSON Schema 只验证结构；下列跨字段、签名、事务和权限规则必须由实现补齐。参考验证器只覆盖离线可验证部分，不代替生产鉴权。

## 1. 版本、命名与继承

- Grant 为 `v2`；执行上下文为 `rd-execution-v2`；结果为 `recruitment-result-v2`；输入哈希为 `rd-input-hash-v1`。这些版本与 RD `/api/v1`、交付版本 `1.1.0` 和结果业务 schema 独立。
- 保留现有 `request_context`、`policy_decision`、`input_versions`、`data_handling`、`business_operation_ref`、`requested_at` 与 `input` 布局，增加路由、授权及哈希字段。HTTP JSON 使用 snake_case，Java DTO 显式映射。`execution_id` 是 IR 执行记录 UUID，等于业务 `attempt_id`；`business_task_id` 对应 Grant `task_id`，与平台 `ai_task_id` 分开。
- 五项招聘操作为 create/analyze/match/revise/generate。当前平台还注册 `conversation_continue` 和 `conversation_route`，本期显式固定 Simple，operation 分别为 continue/route；这两个 operation 是本期新增合同定义，旧代码没有对应持久化字段。不得猜测未注册能力的路由。
- IR 新 Attempt 事务冻结路由、输入和派发 Outbox；授权后追加价格快照。筛选 Run 的子 Attempt 继承根快照。技术 `ai_task_attempts` 不新建业务 Attempt 或授权。
- 配置缺失/非法/版本为空启动失败；production 不限制 Simple。固定 Simple 能力忽略开关并拒绝 Complex。

## 2. Grant 与价格

`grant` Schema 描述验签后的 JWT claims；并非可以发送给平台的签名 Token。BOSS 签发 RS256 compact JWS，Header 含 kid；可保留 jti/sub，存在时必须分别等于 grant_id/task_id；IR→平台服务 Bearer 与 `X-AI-Grant` 原始 JWS 分开，后者不带 Bearer 前缀。校验 issuer/audience、iat/nbf/exp、签名及服务身份；首次 create 必须未过期。已受理任务使用持久化授权继续执行。

绑定 tenant/actor/task/attempt/product_domain/capability/operation/agent/key/config/input，以及 grant/authorization/reservation ID。AEP 小写 capability 必须经 routes 映射到 BOSS 大写 capability。`policy_decision` 的 Tenant、actor、capability 与请求一致，decision=allow；不能代替 BOSS Grant。

Simple 必须携带 model_constraints；Complex 必须携带 external_api_constraints，并且不携带 model_constraints。模型/Tokenizer 在 Complex 结果、台账、技术尝试和结算中为未知或不适用，不填假模型或 0 用量。Simple 执行与结果仍需真实模型身份。

价格只信任 BOSS 保存并签名的 pricing_snapshot。本包的 Token 单价字段保留现有“多少 Token/积分”语义，字段名为 input_tokens_per_credit/output_tokens_per_credit；TOKEN 计算固定为 `max(1, ceil(input_tokens/input_tokens_per_credit) + ceil(output_tokens/output_tokens_per_credit))`，超过预占/maximum_charge 则拒绝并核对，不能错误释放已执行请求；实现使用防溢出整数算术。固定积分单位为整数积分；示例金额非正式售价。FIXED_PER_EXECUTION 单元数=1；FIXED_PER_CANDIDATE 只用于匹配，授权单元数=实际批次人数且为 1～5。TOKEN 缺真实用量保持核对，不能填 0。报价与结算不按现行开关或规则重新定价。

## 3. 输入哈希

对 `hash_material` 精确投影做 SHA-256：包含 hash_contract_version、agent_id、平台 capability、operation、business_task_id、input_versions、files、policy_snapshot、input。每个版本包含 kind/ref/version/content_hash；文件包含 file_asset_id/version/sha256/mime_type/byte_count。数组顺序保留，尤其文件与候选人输入顺序。

- UTF-8，无 BOM、无末尾换行；对象键递归按 Unicode code point 排序；不做 NFC/NFD、trim 或 JD 换行归一化，冻结时提交什么就哈希什么。
- JSON 紧凑分隔符 `,`/`:`，非 ASCII 直接 UTF-8；仅转义 JSON 必需字符，控制字符使用小写 `\u00xx`，保留 JSON 常用转义。禁止重复对象键和孤立 surrogate。
- 哈希材料数字只允许整数，不允许二进制浮点。业务小数（权重、年限等）在该投影中转成规范十进制字符串：无指数，无多余尾零，-0→0；结果 DTO 的小数仍可用 number。超大整数不经 JavaScript Number 中转。
- 缺失字段与显式 null 不等价；可选槽未知时省略，数组及已存在字段不擅自改写。文件临时 URL、expires_at、密钥、Grant、request/trace ID、提交时间和本次 attempt_id 不进入哈希。
- policy_snapshot 包含 IR 冻结规则版本/内容（Simple）或 `policy_source=RD_STANDARD`（Complex）；返回后的 RD policy_ref 不改输入。
- 请求顶层路由、input、input_versions 和上下文必须与 hash_material 相符，IR 与 AEP 都计算比对。文件下载后再次比对 SHA-256。续签 URL 不改变材料。

`fixtures/hash-vector.json` 是跨语言金标，不能直接使用平台当前依赖 Map 插入顺序的 fingerprint 算法。RD OpenAPI Canonical SHA-256 使用主方案第15节原算法，与业务材料规则分开。

## 4. 内部 HTTP 接口

所有接口均为受信任服务身份访问，禁止浏览器访问。IR 是 BOSS 最终上报唯一调用方；平台不调用 BOSS。

| 调用 | 方法/路径 | 请求 / 响应 Schema | HTTP 与语义 |
|---|---|---|---|
| IR→BOSS 授权 | POST `/internal/ai/execution-authorizations` | authorization_request / authorization_response | 200；同 key 同绑定返回原授权，冲突409；不因 Grant 过期签发第二次执行权 |
| IR→AEP 受理 | POST `/api/v1/capability-executions` | execution_request / task | 202；服务 Bearer、X-AI-Grant、Idempotency-Key、X-Request-Id；Header 必须与上下文一致 |
| IR→AEP 查受理 | POST `/api/v1/capability-executions/lookup` | lookup_request / lookup_response | 200，found=false 只表示本次查找未命中，须与受理唯一约束配合；绑定冲突409 |
| IR→AEP 状态/结果 | GET `/api/v1/tasks/{id}`、`/api/v1/tasks/{id}/result` | task / result | 200；结果未可用409；只读，无结算副作用；校验已保存归属 |
| IR→AEP 取消 | POST `/api/v1/tasks/{id}/cancel` | 既有任务绑定 / task | 200，返回实际状态；已发出只保存取消意图，不冒充停止 RD |
| AEP→IR 回调 | POST `/internal/v1/ai/webhooks/task-status`（沿用现有路径） | webhook / accepted、duplicate_or_stale | 202；保留现有 X-Webhook-Signature/X-Webhook-Timestamp 签名认证，重复事件幂等；不携带结果正文 |
| IR→BOSS 受理 | POST `/internal/ai/execution-acceptances` | acceptance / control_response | 200；绑定 accepted_task_id，重放幂等 |
| IR→BOSS 续期 | POST `/internal/ai/execution-heartbeats` | heartbeat / control_response | 200；BOSS 按服务端配置计算 lease，不信任调用方无限延长 |
| IR→BOSS HOLD | POST `/internal/ai/execution-holds` | hold / control_response | 200；期限和证据校验；不以普通 TTL 释放 |
| IR→BOSS 扣费 | POST `/internal/ai/usage-reports` | settlement（CAPTURE） / control_response | 200；保存唯一结论；不接受调用方最终金额 |
| IR→BOSS 释放 | POST `/internal/ai/execution-cancellations` | settlement（RELEASE） / control_response | 200；保留现有传输路径，语义是最终积分释放，不是用户取消意图 |
| AEP→IR 文件授权 | POST `/internal/recruitment/file-download-grants` | download_request / download_response | 200；Tenant/Attempt/任务/文件归属校验；短时私有 URL，仅返回不落任务输入/日志 |

authorization_response.grant 是原 JWS；grant_claims 是与签名一致、由 BOSS 同时返回的授权摘要，供 IR 固化。AEP 只信任已验签 claims，不能以该摘要绕过验签。BOSS 控制接口和最终结算校验全部绑定；平台 result/status 读取与 lookup 可使用保存授权，旧 Grant 到期不阻断只读恢复，更不允许创建新执行。

接口错误统一 `error` Schema：401 服务认证失败，403 权限/Grant/绑定失败，409 幂等或终态冲突，422 输入合同错误，503 readiness/资源不可用；外部调用可能已发出时，即使500/503也只能核对。安全错误码为 CONTRACT_INVALID、GRANT_BINDING_MISMATCH、IDEMPOTENCY_CONFLICT、INPUT_HASH_MISMATCH、UNKNOWN_EXECUTION_STATUS、RESULT_NOT_READY、RESULT_MAPPING_FAILED、OUTCOME_UNVERIFIABLE、RECONCILIATION_DEADLINE_EXPIRED。retry_action 是指导本地处理，不授权重发 RD。

## 5. 统一结果、状态和计费

JD data 包含 jd_text、structured_job 和 requirements_snapshot。生成结构属于生成快照；最终确认 JD 独立版本，匹配只提交 job_text。

简历 data 包含 analysis_text、parsed、analysis_snapshot、warnings。parsed 保存可空姓名/联系方式/年限及完整经历、技能、教育、证书、语言。字段不从正文猜测，不覆盖用户确认值。结构化对象内容遵循锁定 RD schema；Simple 由归一化层提供同一业务合同。

匹配 data 包含逐人 candidate/file/input_order/attachment_ref、evaluation_status、可空 score/level、result_validity、billable_unit_count、原因和证据。score 原值0～100；等级按85/70/60阈值，显示舍入不改保存值。每个输入唯一、完整关联；未评分不伪造0分。RD 原始 status 是字符串，只有显式支持且证据一致的值可解释成功；未知值进入核对。批次人数/引用/计数验证属于语义约束。

统一结果可先作为本地保存的待核对/映射失败诊断记录，status 与平台状态相符；只有已验证的 completed 结果可发布完成事件，任何未决候选人或计费资格未知项都不能让批次假装完成。核对期限关闭的无结果记录保存 failed 诊断。

routes/states 表为唯一状态映射。新增三种状态非终态；全部候选人有已确认终态时，部分失败批次仍可 completed。state_version 在平台状态事务中递增并与 webhook Outbox 同事务，event_id 去重、旧版本不回退。IR 本地保存失败独立记 PROCESSING_FAILED/HOLD，不回退平台终态。cancellation_requested 贯穿实际执行状态；取消后结果只用于审计及计费。

逐项计费：VALID_BUSINESS_RESULT 计1，CONFIRMED_NO_RESULT计0，UNVERIFIED_RESULT 保持未知/HOLD；最大核对期限关闭仍保留 UNVERIFIED_RESULT，原因为 RECONCILIATION_DEADLINE_EXPIRED，关闭时计0。P1 有未知项时不发最终 settlement，全部确定后 executed_unit_count=逐项和，且≤授权数量。CAPTURE/RELEASE 与结果或取消审计在 IR 同事务决定并排入唯一 Outbox。decision_fingerprint 使用同一规范序列化对 settlement 除 decision_fingerprint 本身的全部字段计算 SHA-256；重复相同指纹返回已有结论，冲突409；BOSS reservation 行锁只允许一个终态。迟到报告不改已关闭结果和费用。

## 6. RD Direct 与运行约束

只调用 `/api/v1/ready`、JD Generate、Resume Analyze、Resume Match。Analyze multipart字段 file；Match仅 job_text+重复resumes，不发送 generated structured_job、IR权重、Conversation幂等键。每文件PDF/DOCX≤10MiB；Match1～5份、job_text≤100000字符，读取窗口≥600秒。

Worker在HTTP前持久化 REQUEST_DISPATCHING；PREPARING可本地重试，可能发出后崩溃/断连仅核对。RESPONSE_RECEIVED先加密保存完整响应再映射统一结果，最后完成。RD无通用结果查询/轮询/取消/Direct幂等 API，不提供自动fallback。

实际密钥、端点、发布价格、租约/续期间隔、首次核对期限、最大核对期限、存储厂商和结果保留配置属于部署输入；未提供不阻碍 Fake 开发，实际调用前必须齐备。维护门禁首次v2升级覆盖全部使用Grant的新根入口，排空v1后同步升级；日常路由切换仅暂停三项重叠能力。
