# Phase 0 验证记录

2026-10-07：16 项测试通过（离线Schema/语义校验与127.0.0.1 Fake HTTP）。

执行环境：独立临时venv，Python 3.14，jsonschema 4.23.0。

覆盖RD锁定哈希、外部合成样例、内部Schema、Grant专属约束/绑定、输入哈希、空用量/可空年限、生成结构不能作为Match输入、状态映射、未评分与核对计费、readiness认证、JD JSON及Analyze/Match multipart（单人/5人/非法人数和字段）。

没有执行Java运行时、数据库、生产鉴权/签名、Worker或真实供应商验收。

```text
test_agent_constraints (__main__.ContractChecks.test_agent_constraints) ... ok
test_binding_and_hash (__main__.ContractChecks.test_binding_and_hash) ... ok
test_external_lock (__main__.ContractChecks.test_external_lock) ... ok
test_external_samples (__main__.ContractChecks.test_external_samples) ... ok
test_generated_job_is_not_match_input (__main__.ContractChecks.test_generated_job_is_not_match_input) ... ok
test_input_mismatch (__main__.ContractChecks.test_input_mismatch) ... ok
test_internal_schemas (__main__.ContractChecks.test_internal_schemas) ... ok
test_nullable_usage_and_resume (__main__.ContractChecks.test_nullable_usage_and_resume) ... ok
test_semantic_conflicts (__main__.ContractChecks.test_semantic_conflicts) ... ok
test_settlement (__main__.ContractChecks.test_settlement) ... ok
test_states (__main__.ContractChecks.test_states) ... ok
test_unscored_results (__main__.ContractChecks.test_unscored_results) ... ok
test_jd (__main__.HttpChecks.test_jd) ... ok
test_match (__main__.HttpChecks.test_match) ... ok
test_ready_and_auth (__main__.HttpChecks.test_ready_and_auth) ... ok
test_resume (__main__.HttpChecks.test_resume) ... ok

----------------------------------------------------------------------
Ran 16 tests in 5.874s

OK
```
