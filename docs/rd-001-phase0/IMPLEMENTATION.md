# RD-001 实施清单与依赖

Phase 0 已落实源码基线、外部快照、内部字段/语义合同、状态/路由矩阵、哈希金标和合成 HTTP 服务。下表是后续业务实施清单，未表示业务代码已完成。

| ID | 阶段/仓库 | 具体事项与产物 | 前置 | 验收 |
|---|---|---|---|---|
| B01 | Phase1 BOSS | 四维 Rule、精确查询、发布/退休事务与价格，回填旧 Simple 规则，新增3项Complex | Phase0 | 同能力双Agent可发布，同路由只一条；缺规则拒绝，无fallback |
| B02 | Phase1 BOSS | Grant v2、授权摘要、幂等绑定、条件模型/外部约束 | B01 | 缺绑定/约束拒绝；同key换路由/输入409；Simple缺模型仍拒绝 |
| B03 | Phase1 BOSS | TOKEN/FIXED_PER_EXECUTION/FIXED_PER_CANDIDATE按授权快照计算 | B02 | 固定usage=null准确扣费；TOKEN缺用量HOLD；计数和扣费上限校验 |
| B04 | Phase1 BOSS | acceptance/heartbeat/hold、reservation租约/关闭，credit_lots到期保护 | B02 | 已受理/HOLD不被TTL释放；过期lot release不恢复可用余额；迟到报告不重扣 |
| I01 | 基础 IR | 移除getStructuredResult/cancel/Grant过期扫描结算副作用，ExecutionCompletionService、final_decision与唯一Outbox事务 | B02/B04 | 读结果无扣费；取消不直接release；并发只一个最终决定 |
| I02 | 基础 IR | 授权/结果/取消审计/台账/Outbox模型可空与RELEASE语义迁移，旧待派发效果核对 | I01 | Complex null不触发Map.of/NOT NULL，保留V4唯一约束；旧事件不盲发 |
| A01 | Phase2 AEP | Simple/Complex Adapter、三维注册表、固定Simple组合 | B02 | 同能力两路可注册；改写/面试题Complex拒绝，原Simple能力回归 |
| A02 | Phase2 AEP | v2验签与受理持久化、lookup、同key完整绑定，ai_tasks/attempts可空及markRunning条件校验 | A01 | 回执丢失恢复同一Task/Grant；技术重试不新增业务授权 |
| A03 | Phase2 AEP/IR | states矩阵、state_version、webhook Outbox、查询投影、终态不可回退 | A02/I01 | 新增非终态正确映射；重复/乱序无回退；未知状态不默认RUNNING |
| A04 | Phase2 AEP | 异步RD Worker、PREPARING/REQUEST_DISPATCHING/RESPONSE_RECEIVED、续租、核对工单、本地结果修复 | A02/A03/B04 | 请求可能已发出不重发；响应先加密保存；Mapper失败本地恢复 |
| I03 | Phase2 IR | 配置fail-fast、RouteResolver、根Attempt/输入/Outbox事务、筛选Run子任务继承 | I01/A02 | 开关仅新根生效，生产允许Simple，旧Run不混路由 |
| I04 | Phase2 IR | 统一结果v2消费、输入哈希双端一致、公共请求拒绝内控字段 | I03/A03 | 金标一致；临时URL不影响hash；用户无Agent入口 |
| J01 | Phase3 AEP/IR | ready与JD slots、Direct响应、草稿/生成结构/用户确认JD版本 | A04/I04 | HTTP200且overall_ready；JD草稿确认不覆盖生成快照；无fallback |
| F01 | Phase4 IR/AEP | ObjectStorage接口、私有文件授权/续签、下载白名单/大小/MIME/hash/重定向约束 | A04/I03 | URL过期可续签但不重调RD；跨Tenant/文件拒绝；URL不落日志/input |
| R01 | Phase4 AEP/IR | structured_resume.data完整映射、年限nullable decimal、候选人/解析版本/API/筛选/展示迁移 | F01/I04 | 小数不截断、未知不为0、经历对象不toString、人工确认值不覆盖 |
| S01 | Phase5 P0 IR/AEP | 每人独立Attempt/Grant/Task；最终JD job_text+一份简历 | F01/R01/J01 | 编辑确认后的JD作为匹配输入；不传generated structured_job或IR权重 |
| S02 | Phase5 P0 IR | Admin规则权限、policy_source、score/level可空、终态/证据/进度/排序 | S01 | 硬过滤/正常未评分完成且无假分；未知项核对；普通用户不能改规则 |
| S03 | Phase5 P0 IR/BOSS | 独立validity/billable判定与唯一最终结算 | S02/B03/I01 | 有效/硬过滤/正常未评分计1，失败计0，缺失矛盾HOLD，取消按实际结果 |
| S04 | Phase5 P1 IR/AEP/BOSS | 1～5人批次授权/映射/逐人结果/整批结算 | P0闭环稳定 | 一批一Grant/Task；有未知项不提前结算；关闭后一次单元求和 |
| C01 | Phase6 三仓库 | 改写/面试题固定Simple与原能力/价格回归 | A01/I04 | 两种开关都Simple，无客户端覆盖，无RD面试题路由 |
| O01 | Phase7 IR | 共享维护门禁、内部验证入口、根创建事务锁、切换/回滚脚本 | 前述P0全部 | DRAINING禁止新根；旧子任务/回调/续期继续；客户不能绕过 |
| O02 | Phase7 三仓库 | v1排空、v2同步发布、完整版本矩阵、Fake集成/迁移/真实授权联调 | O01 | 首次升级覆盖所有Grant入口；固定版本组合可复现；真实RD经授权验证 |

## 迁移范围与基线

当前源码迁移最高编号为 BOSS V26、AEP V1、IR V6；只是仓库文件状态，不是实际数据库已执行状态。后续使用新的空闲编号（当前可从V27/V2/V7起），创建前再核查，禁止修改已发布旧文件。

BOSS：ai_feature_rules索引/规则/价格；ai_execution_authorizations；usage_reports可空模型/用量；credit_reservations受理/租约/HOLD/最终关闭；credit_lots与reservation_items到期关系。

AEP：ai_tasks和ai_task_attempts的模型可空、路由/业务attempt/授权快照；状态约束与版本；RD派发证据、原始响应加密引用、核对记录、webhook可靠Outbox。

IR：ai_execution_records绑定/路由/最终决定/取消/结算；ai_settlement_outbox从CANCELLATION→RELEASE；保留V4 `UNIQUE(execution_id)`；根Run及候选人执行快照；Candidate/ResumeParseVersion年限decimal可空与结构化JSON；screening_run_items状态及screening_results分数等级；共享维护门禁。

## 测试边界

本包只完成离线Schema、语义示例和Fake传输校验。以下验收留给相应实施阶段：实际Java序列化/跨语言哈希、数据库升级、签名/JWKS/服务鉴权、幂等并发与行锁、真实Outbox、Worker恢复/租约、结果加密/文件下载、Admin权限、前端状态、完整积分账本与切换发布。

P0不包含批次业务编排。Fake能返回1～5人只用于冻结RD合同；不表示P1运行时完成。不要以所有候选人score非空、RD HTTP200、用户已取消或已有结果行直接推导计费资格。

## 部署前待填项

- 运营发布各路由价格、计费方式、权限与权益规则版本；示例金额不可用于正式售价。
- 环境Base URL、RD/DeepSeek密钥版本、签名/内部认证/webhook/加密凭据。
- 对象存储实现、私有bucket/endpoint、文件和原始结果保留策略。
- 执行租约、续期间隔、限流并发、下载/连接/读取超时、核对责任人及处理期限。
- 三仓库业务代码commit、发布制品、实际Flyway状态和配置版本。

这些是联调/发布输入；不阻碍Phase1～6使用Fake和独立测试账本实施。
