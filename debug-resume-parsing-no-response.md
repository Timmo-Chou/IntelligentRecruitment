# Debug Session: resume-parsing-no-response

- Session ID: `resume-parsing-no-response`
- Status: `[OPEN]`
- Created: 2026-09-02
- Symptom: 用户在首页选择「AI简历解析」模式，点击发送后，后端没有任何反馈（详情页文本框一直空/一直 loading/无解析结果）。
- Expected: AI 回复 Markdown 摘要（基本信息/教育/经历/技能/亮点风险/职位匹配/后续建议 7 段）填入 ResumeParsingWorkspace 文本框。

## 可证伪假设（H1–H5）
| ID | 假设 | 观测点 |
|----|------|--------|
| H1 | **前端 createTask 请求未真正发起** / 返回非 200，导致后端任务不存在（最常见入口丢失） | `POST /api/recruitment/workspaces/:id/tasks` 的请求体/响应；父 handleCreate 的 requirement 条件是否又被跳过？ |
| H2 | **后端 createTask 因 linked_candidate_id 列不存在而 SQL 失败**，任务未入库 → worker 无任务可跑 → outbox 不触发 → AI platform 永远不被调用 | 后端 recruitment_tasks 表的 V24__resume_parsing_linked_candidate.sql 是否执行？CreateTaskInput/RecruitmentService.save 是否真正写入 linked_candidate_id？ |
| H3 | **ResumeParsingWorkspace 轮询/订阅未正确绑到新 task**，或 taskId 与详情页不匹配 → 前端看不到解析，但后端其实已跑完 | 详情页 URL `?task=` 参数 vs 前端 `selectedTask`；worker 写入 resume_parse_drafts.markdown 字段；前端 ResumeParsingWorkspace useEffect 是否监听 workspace/task 变化？ |
| H4 | **DeepSeek 适配器报错被 catch 后静默，只 set FAILED 无日志无结构化结果**，前端对 FAILED 不做提示（空）→ 用户感觉"没反馈" | startTask L116-139 的 try/catch；generateResumeParse 是否真调用 DeepSeek /chat/completions；API Key 配置 app.ai-platform.allow-external-data / api-key 是否为空；结果是否写回 resume_parse_drafts。 |
| H5 | **父 handleCreate featureRequirementMet 与子 canSubmit 不一致**（Enter 键子→父 onCreate 传入 `files=undefined`；父用了 sourceFiles，子 uploadedFiles 传 `onCreate(uploadedFiles.map(...))`）导致前端"看似发送"但后端 resumes 空 → 走 Mock 只返回"请先上传简历"且被前端当无结果。 | handleCreate 中 `sourceFiles` 与传入 onCreate 的文件是否一致；createTask extra.sourceFiles 最终转换为 resume_source_files 表的 SQL 日志。 |

## 步骤规划
1. 静态代码走查：父 page.tsx → RecruitmentService.createTask → OutboxWorker → AiPlatformClient.startTask → hook 写回 resume_parse_drafts。
2. 插桩：在上述每个关键节点打 TRAE-debug log（前端 createTask 前后、后端 createTask/worker/AiPlatform 入口与出口）。
3. 让用户复现一次「AI简历解析发送」，采集日志证据。
4. 根据证据锁定具体假设，最小化修复。
5. 修复后再次复现对比日志，确认 post-fix 正常。

## 运行时证据
（待插桩后填写）

## 结论与修复
（待证据确认后填写）
