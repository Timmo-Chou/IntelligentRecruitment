# RD execution record — 2026-10-09

## Scope and safety

- Ran BOSS, AIAgentPlatform, and IntelligentRecruitment against isolated PostgreSQL databases `rd_boss_fresh`, `rd_aep_fresh`, and `rd_ir_fresh`, isolated Redis DB 15, RabbitMQ vhost `/rd-validation`, local MinIO bucket `rd-validation`, and Fake providers on loopback.
- Used only the synthetic tenant and identities configured by `scripts/rd-e2e/bootstrap.py`; no real RD endpoint or production credential was used.
- Current worktrees remain dirty on their existing feature branches. No commit or push was made. The canonical `三系统端到端测试文档.md` was updated in the shared workspace.
- The isolated IR release gate was restored to `OPEN` for the previously validated cutover after maintenance-gate checks.

## Executed evidence

- `scripts/rd-e2e/smoke.py`: full Fake P0 flow for JD generation and confirmation, private PDF grant/download, resume parse with decimal years and structured work-experience object, Admin-only screening rule management, one-candidate matching, and settlement. Result: pass. Proof: `/tmp/rd-e2e/smoke-proof.json`.
- `scripts/rd-e2e/acceptance_modes.py`: six Fake modes. Hard-filter and normal not-evaluated each captured one unit; confirmed failure released zero; unknown result, mapping failure, and disconnect stayed unresolved/HOLD. Each case issued one RD request; mapping failure saved encrypted raw response before reporting `RESULT_MAPPING_FAILED`; disconnect stayed at `REQUEST_DISPATCHING`. A 15-second late-repeat observation showed no second Match request. Proof: `/tmp/rd-e2e/mode-proof.json`.
- Admin browser: logged into the local Admin with a disposable account in the isolated BOSS DB. The AI rule list displayed the three Complex capability/operation rows, entitlement, fixed test price and cap, plus external-contract applicability. The form conditionally omitted Simple model/token limits and disabled Complex TOKEN. This was a read-only UI verification; no rule was edited or retired.
- Release gate: isolated maintenance API completed DRAINING → VALIDATING → controlled validation execution settled → OPEN. Ordinary JD root creation returned HTTP 503 in DRAINING and VALIDATING; invalid control credentials returned 401. A fresh cutover could not be opened without its own validation (409). The previously validated cutover was restored and checked OPEN.
- Initial same-day run before the follow-up changes: Fresh DB Flyway histories reached BOSS V29, IR V16, and AIAgentPlatform V3.
- Initial same-day Maven run before the follow-up changes: BOSS 38 tests, AIAgentPlatform 13 tests, IR recruitment-service 30 tests; zero failures/errors. The current follow-up totals and migration versions are recorded below.
- Maven package (`-DskipTests`) succeeded for all three services.

## Remaining acceptance gaps

- No real RD address/key or explicit invocation authorization was available; no real provider call or content-quality review was performed.
- Formal Complex prices and commercial entitlements remain unconfirmed; isolated test price 7 is only a fixture.
- Existing-database migration matrix (BOSS V25, IR V6, platform V1), complete checksum/constraint audit, and PostgreSQL integration tests with the project-specific opt-in are still required.
- Admin form submission, invalid rule combinations, write-permission denial, config false/invalid startup matrix, and application-level route rollback were not executed.
- Settled-result replay, cancellation races, closure deadlines, callback ordering/state-version races, callback loss lookup, local repair-result action, multi-instance gate races, and full file-reference renewal/ownership negatives remain incomplete.
- At the time of this initial record, P1 batch execution was unimplemented.

## Follow-up execution update — 2026-10-09

- Preserved the execution-start source state before editing in `/Users/zhouwanchen/Documents/ChatGPT/RD-baseline-2026-10-09`; tracked/index patches, untracked archives, contract and migration hashes are recorded there. No existing working-tree content was reset or deleted.
- Implemented the P1 batch close path: result mapping follows frozen `batch_order`; each candidate's qualification is stored independently; unresolved candidates keep the batch on HOLD; deadline close retains verified billable units and closes only unknown candidates at zero; one aggregate decision carries the candidate summary for BOSS validation. BOSS rejects mismatched summary counts. Full multi-candidate business E2E and crash/late-result races remain open.
- Added a PostgreSQL test for P1 deadline partial capture: a 3-candidate authorized batch captures 2 verified units with 1 deadline-closed unknown, and rejects an inconsistent summary. The test is synthetic and rolls back its fixtures.
- Re-ran default Maven suites after the current source edits: BOSS 39, AIAgentPlatform 14, IntelligentRecruitment 30; all had zero failures/errors. The opt-in PostgreSQL tests skipped by default were BOSS 5, AEP 2, IR 1.
- Used an isolated PostgreSQL 17.11 container on a disposable local port. Fresh migrations and Flyway validate passed at BOSS V29, AEP V5 and IR V21. Historical schema upgrade paths passed from BOSS V25, AEP V1 and IR V6 to their current versions. These upgrade databases contained the prior-version schema/history but no legacy business rows; old price, audit, reservation and Outbox row preservation still needs a populated upgrade fixture.
- AEP raw-response retention test verified the configured 90-day rule: a 91-day terminal task had both encrypted response bodies cleared and received a `RAW_RESPONSE_EXPIRED` audit event; an active task retained its encrypted response. Default settings are `AI_RAW_RESPONSE_RETENTION_DAYS=90` and `AI_RAW_RESPONSE_CLEANUP_MS=3600000`; the worker caps each pass at 500 rows.
- AEP background dispatch, heartbeat, side-effect, repair and retention schedulers are excluded from the `test` profile so H2 context tests do not execute PostgreSQL-only background SQL. PostgreSQL lifecycle tests instantiate and run the retention worker directly.
- Phase 0 Fake contract/HTTP verifier passed all 16 tests in a temporary Python environment. No RD key, real provider call, production database, or external system was used.
- Added `RD-operations-runbook.md` with retention configuration, recovery constraints, database queue/hold checks and release-time operational inputs. Alert thresholds, notification destinations and reconciliation ownership/timings remain deployment/operator decisions and are not hard-coded.

### Follow-up acceptance still required

- Populate old-version migration databases with synthetic legacy Simple prices, audit rows, reservations and pending Outbox items before upgrade, then verify preservation and constraints.
- Exercise lookup receipt loss/duplicate bindings, event ordering/conflicts, cancel/success/close races, Outbox replay failure, and multi-instance deadline close.
- Kill/restart AEP around `REQUEST_DISPATCHING` and response persistence, verify lease recovery/local repair, and prove no duplicate Direct call.
- Run full IR P1 batch flow through Fake RD for 1/5 candidates, mixed outcomes, unknown HOLD, cancellation, deadline close and late response; verify a single BOSS final decision.
- Complete file-URL renewal and malformed PDF/DOCX, origin/path, redirect, size, hash/MIME, wrong-tenant/task and environment-configuration negatives.
- Finish Admin write/permission and true/false route/cutover/rollback matrices. Obtain real RD address/key and explicit invocation approval only when moving to real-provider acceptance.


### Recovery test update — 2026-10-09

- Added `TaskExecutionStateServiceRecoveryTest`: an expired-lease attempt at `REQUEST_DISPATCHING` now has a regression assertion that resumes only into `RECONCILIATION_REQUIRED`; an attempt at `RESPONSE_RECEIVED` resumes only into `RESULT_MAPPING_FAILED` while retaining the encrypted response for local repair. Neither path returns a task to the external handler.
- AEP default suite is now 16 tests, 0 failures/errors, 2 opt-in PostgreSQL tests skipped. On this Java 25 host, Mockito/Byte Buddy requires the in-process test invocation `mvn -q -DforkCount=0 -Dnet.bytebuddy.experimental=true test`; this completed successfully. This is a state-transition regression test, not a substitute for killing/restarting a worker and observing actual lease reclaim and Direct call counts.
