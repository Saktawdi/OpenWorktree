# Phase3 可水平扩展执行面 验收证据包

状态：Verified（本地验证）  
基线提交：`df860e3` + Phase3 增量  
范围：PostgreSQL 兼容 claim / 权威 Git CAS / 隔离 Worker / S3-KMS 最小适配

## 1. 交付清单

| 产物项 | 交付物 | 证据 |
|---|---|---|
| PostgreSQL 适配器 | `JdbcGateTaskRepository implements TaskClaimPort` + `V11__phase3_nonce_and_task_indexes.sql` | `claimNext/renewLease/reapExpiredLeases` + 索引 `ix_gate_task_claim/lease` + `ux_gate_task_idempotency` |
| 权威 Git CAS | `AuthoritativeGitService` + `LocalAuthoritativeGitService` + `NonceStore/JdbcNonceStore` + `V11 gate_nonce` | `casPublish` 原子：`expected_old == actual_old` + `nonce` 单次消费 + `update-ref` 事务 |
| 隔离 Worker | `FsEphemeralWorkspaceManager` | `allocate/reconstruct/release/gcOrphans` + `isIsolated` 校验 |
| S3/KMS | `FsS3Store` + `LocalKmsService` + `V12__phase3_s3_kms.sql` | digest 条件写 + 版本化 + HMAC 轮换 |
| 事件/Outbox | 已在 Phase2 完成，Phase3 复用 | `task_event/outbox` + `TaskClaimPort` 同 DB |
| HA 部署 | `GateRuntime` Phase3 wiring | PG 兼容短事务 `priority DESC, available_at ASC` 领取 |

## 2. Phase3 退出条件验证

| 退出条件 | 方法 | 结果 |
|---|---|---|
| 任意 Web 节点可查询/订阅任意任务 | `Phase3HaFaultTest.testAnyWebNodeCanQueryAndSubscribeAnyTask` 双 Jdbc 实例共享 DataSource | passed |
| 任意兼容 Worker 可接管可恢复任务 | `testAnyWorkerCanClaimAndReaperTakeover` enqueue 2 任务 + claimNext + renewLease + reaper RETRY_WAIT + 重领 | passed |
| 并发 CAS 仅 1 成功 | `testConcurrentCasAtMostOneSuccess` 2 线程同 old OID 推不同 commit via `LocalAuthoritativeGitService` | passed 1/1 |
| PG/Git/Worker 故障不重复发布 | `testPgGitWorkerFaultDoesNotDuplicatePublish` 模拟 crash 后 nonce 幂等 + 旧 OID 冲突 | passed |

全部 4 项在 `gate-adapters` 本地 SQLite 模拟 PG 语义下通过（`Phase3HaFaultTest 4/4`，`TaskEventOutboxFaultTest 6/6`）。

## 3. 关键设计对齐

- **领取**：PG 用 `FOR UPDATE SKIP LOCKED` CTE（SQL 注释），SQLite 回落为 `SELECT id LIMIT 1` + `UPDATE ... WHERE status IN ('QUEUED','RETRY_WAIT')` 原子；排序固定 `priority DESC, available_at ASC, id ASC`（ADR-002）。
- **租约**：默认 60s，续租周期 1/3-1/2；`renewLease` 校验 `id+lease_owner+fence_token+RUNNING`；`staleRejections` 计数。
- **Sequence**：`append` 按 `taskId.intern()` 同步，`UPDATE next_event_sequence+1` + `SELECT` + `INSERT` 模拟 PG `RETURNING` 原子。
- **Git CAS**：`PublishHandler` 当 `authoritativeGitService != null` 时走 `casPublish`，否则回落直接 push；`GateRuntime` 已注入。
- **Nonce**：`gate_nonce(nonce PK)` 与 ref 更新同一文件锁事务；`tryConsume` 唯一约束防重放。
- **S3**：`put` 校验 `sha256` + `size`，`putConditional` 检查 `versionId`，`head` 校验；`FsS3Store` 为本地 mock，接口与 S3 一致。
- **KMS**：`LocalKmsService` HMAC-SHA256，支持 `keyRing` 当前/上一代、撤销检查；`sign/verify` 覆 canonical JSON + JCS。

## 4. 命令与测试

```powershell
powershell -File docs/tools/verify-governance.ps1       # passed (phase_3 Verified, team Verified)
powershell -File docs/tools/verify-contract.ps1         # passed 12 migrations
powershell -File docs/tools/verify-fault.ps1            # 6 scenarios passed
powershell -File docs/tools/verify-capacity.ps1         # MAX_BUFFERED_EVENTS=100 passed
mvn -Dtest=gate.adapters.store.Phase3HaFaultTest -pl gate-adapters -am test  # 4/4
mvn -Dtest=gate.arch.ArchitectureTest -pl gate-cli -am test                 # 8/8
```

## 5. 剩余风险与 Phase4 前置

- 真实 PG 集群、PATRONI/ PGBouncer、WAL 归档未在本地验证（仅 PG 兼容 SQL）。
- 权威 Git 企业级 pre-receive hook、Ed25519、JCS 规范序列化未替换 HMAC（`docs/adr/003` 已批准，需后续服务化）。
- Worker 沙箱仍为 `FsEphemeralWorkspaceManager` 文件隔离，非容器/gVisor；`isIsolated` 仅防路径穿越/Docker socket。
- `GOV-API-001/GOV-OBS-001` 仍待实现，`enterprise` 仍 Blocked。

下一步：Phase4 企业安全与 SLO（RBAC、WORM 审计、OTel、备份恢复演练）。
