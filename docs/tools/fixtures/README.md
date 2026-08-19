# Negative Fixture: 过期豁免

用于验证 `verify-governance.ps1` 的过期豁免检测能力（GOV-CPLX-001）。

## Fixture 内容

`expired-exemption-case.md` 模拟了一条已过期但仍为 `Proposed` 的 EX 记录：

```
| EX-999 | GOV-CPLX-001 | gate-web/ExpiredFixture.java | 测试 | Test owner | 待指定 | 2025-01-01 | 2025-01-10 | 2025-01-15 | 无 | Proposed（无批准，不生效） |
```

- 到期日 `2025-01-15` 早于今日，状态非 `Expired` → 应被判定为过期失败
- `verify-governance-negative.ps1` 会临时注入该行到 `exemption-register.md` 副本并执行校验，预期 `exit 1`

## 运行

```powershell
powershell -File docs/tools/fixtures/verify-governance-negative.ps1
# 预期：passed=false, errors 包含 "exemption expired"
```
