# Prism 0.5.0 JSON Schema 实测结论

状态：历史技术证据  
原始验证日期：2026-08-13  
保留原因：当前 Prism adapter、策略和成本指标代码仍依赖这些实测结论。

## 结论

使用包含已知 SQL 注入改动的 dangling commit 执行真实 `prism review commit`，确认当前领域 Finding 模型可兼容 Prism 输出，无需为 Prism 引入基础设施类型。

实测得到三项需要由 adapter 吸收的差异：

1. 本次验证的 Prism 二进制版本为 `0.5.0`，版本必须运行时探测，不得硬编码。
2. Prism 使用 `prism version` 子命令，不支持以 `--version` 作为探活协议。
3. OpenAI provider 读取 `PRISM_OPENAI_BASE_URL`，且值为完整的 `/chat/completions` endpoint；项目配置中的网关 base URL 由 adapter 转换。

真实输出具有以下关键结构：

- `summary.counts.low/medium/high`
- `summary.highestSeverity`
- `findings[].severity/category/title/message/suggestion/confidence`
- `findings[].locations[].path`
- `findings[].locations[].lines.start/end`
- `timing.gitMs/llmMs/totalMs`

Prism 0.5.0 不提供可靠的 token/usage 字段。因此：

- Review token 成本必须标记为不可用或 degraded，禁止伪造为 0。
- `coveredPaths` 不能从 Prism 原始输出直接证明；adapter 的兼容推导必须保留 degraded/策略约束。
- 未知 severity 必须按 Fail-Closed 映射为阻断级并标记 degraded。

## 适用边界

这些结论只适用于已验证的 Prism 0.5.0。升级 Prism 后必须重新采样真实 JSON，并通过 adapter 契约测试确认字段、退出码、环境变量和覆盖语义没有漂移。
