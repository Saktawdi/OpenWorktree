# 编码规范参考

本目录收录项目采用的外部编码规范与内部落地细则，所有规范均为 `docs/` 统一入口的一部分（见 `docs/README.md` 文档治理规则）。

| 规范 | 版本 | 来源 | 本地路径 |
| --- | --- | --- | --- |
| 阿里巴巴 Java 开发手册 | 黄山版 1.7.1 / 2022-02-03 | [alibaba/p3c](https://github.com/alibaba/p3c) | `docs/standards/alibaba-java-coding-guidelines/` |

## 使用方式

1. 新代码默认遵循 **强制** 级别全部条目；**推荐** 条目在 Code Review 中作为阻断项（除非 ADR 豁免）。
2. 通过 `p3c-pmd` / IDEA `Alibaba Java Coding Guidelines` 插件进行静态扫描（见 `p3c-integration.md`）。
3. 与本项目架构基线冲突时，以 `docs/architecture/production-architecture.md`、`docs/adr/*` 和 `docs/architecture/governance.md` 为优先，冲突需提 ADR/豁免而非直接绕过。

## 目录

- `alibaba-java-coding-guidelines/README.md` — 手册简介、获取方式、在本项目中的定位
- `alibaba-java-coding-guidelines/coding-guidelines-summary.md` — 七大维度精简速查（强制/推荐/参考分级）
- `alibaba-java-coding-guidelines/p3c-integration.md` — Maven / IDE / CI 接入指南（适配本项目 gate 多模块结构）
- `alibaba-java-coding-guidelines/*.pdf` — 原版 PDF（黄山版），离线查阅
