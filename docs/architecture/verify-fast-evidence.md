# verify-fast 运行证据包 (Phase 0/1 闭环)

执行日期：2026-08-20  
执行人：Sisyphus (L3)  
规则覆盖：`GOV-DEP-001` (ArchUnit), `GOV-BOOT-001` (组合根无暴露), `GOV-DOC-001` (Markdown断链与SLA), `GOV-CPLX-001` (复杂度基线)

## 1. 执行命令与退出码

```powershell
powershell -File docs/tools/verify-fast.ps1
# exit 0, passed=true
```

## 2. 规则结果清单

| 规则 | 检查项 | 结果 | 证据 |
|---|---|---|---|
| `GOV-DEP-001` | 六边形依赖方向 + 无循环依赖 | PASS (8/8 tests) | `gate.arch.ArchitectureTest` |
| `GOV-BOOT-001` | `GateRuntime` 公开 API 无 Spring 暴露 | PASS | `ArchitectureTest.bootstrap_does_not_expose_spring_types` |
| `GOV-DOC-001` | 40+ 文档本地链接可达、ADR/Debt/Owner 注册表一致 | PASS | `verify-governance.ps1` errors=0 |
| `GOV-CPLX-001` | `ApiRoutes` (1478 <= 1538基线), `GateServiceImpl` (620 <= 663基线) | PASS (delta < 0) | `complexity-baseline.json` 测量复现 |

## 3. Negative Fixture 证据

- `powershell -File docs/tools/fixtures/verify-governance-negative.ps1`
- 成功捕获注入的过期 `EX-999` 豁免并返回 non-zero，恢复后 exit 0。

## 4. 结论

`verify-fast` 本地入口已完全固化，满足 Phase 0 退出条件与 Phase 1 受控拆分前置条件。
