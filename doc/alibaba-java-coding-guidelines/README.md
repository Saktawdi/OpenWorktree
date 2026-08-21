# 阿里巴巴 Java 开发手册（黄山版）— 编码规范参考资料

> 本目录为 **阿里巴巴 Java 开发手册** 的本地镜像与落地说明，作为本项目的编码规范参考资料。原文版权归阿里巴巴及全球 Java 社区开发者所有，遵循 Apache-2.0（p3c 仓库 `license.txt`）。

## 基本信息

| 项 | 内容 |
| --- | --- |
| 规范名称 | 阿里巴巴 Java 开发手册 |
| 本地版本 | 黄山版 1.7.1（2022-02-03 发布） |
| 上游仓库 | [alibaba/p3c](https://github.com/alibaba/p3c) |
| 原版 PDF（中文） | `Java开发手册(黄山版).pdf`（本目录，已从上游 raw 拉取，1.49 MB / 55 页）<br/>`Alibaba-Java-Coding-Guidelines-Huangshan.pdf`（同文件英文别名） |
| 英文版在线 | [Alibaba Java Coding Guidelines](https://alibaba.github.io/Alibaba-Java-Coding-Guidelines) |
| 配套图书 | 《码出高效：Java 开发手册详解》（36万字，2018） |
| 配套工具 | `p3c-pmd`（PMD 49 条规则实现）+ IDEA / Eclipse 插件（4 条 IDE 侧规则） |
| 约束分级 | **【强制】**必须遵守；**【推荐】**建议遵守；**【参考】**结合场景采纳 |

> 校验：`curl.exe -L https://raw.githubusercontent.com/alibaba/p3c/master/Java%E5%BC%80%E5%8F%91%E6%89%8B%E5%86%8C(%E9%BB%84%E5%B1%B1%E7%89%88).pdf -o Java开发手册(黄山版).pdf` 拉取成功，页数 55，字符约 73k，已通过 `pymupdf` 抽样验证前言与目录完整。

## 目录结构

```
docs/standards/alibaba-java-coding-guidelines/
  Java开发手册(黄山版).pdf                         # 原版 PDF（中文）
  Alibaba-Java-Coding-Guidelines-Huangshan.pdf    # 同文件别名，便于英文检索
  README.md                                        # 本文件：简介与定位
  coding-guidelines-summary.md                     # 七大维度精简速查（必读）
  p3c-integration.md                               # 在 gate 多模块 Maven 项目中的接入指南
```

`doc/alibaba-java-coding-guidelines/Java开发手册(黄山版).pdf` 为兼容 `doc/` 路径的同文件副本（项目规范以 `docs/` 为唯一生效入口，见 `docs/README.md`）。

## 手册全景（黄山版）

手册以 **Java 开发者为中心**，划分为 7 个维度，细分子类并按约束力分级：

1. **编程规约**（11 小节）：命名风格、常量定义、代码格式、OOP、日期时间、集合处理、并发处理、控制语句、注释、前后端、其他（含正则/BeanUtils/velocity/Math.random/enum 等）
2. **异常日志**：错误码（5 位 `A/B/C+4位`，00000 表示正常）、异常处理（不以 catch 做流程控制、事务回滚、finally 关闭资源、RPC 捕获 Throwable 等）、日志规约（SLF4J 门面、日志分级与占位符、additivity=false、禁止 System.out / e.printStackTrace 等）
3. **单元测试**：AIR（Automatic / Independent / Repeatable）、BCDE（Border / Correct / Design / Error）、覆盖率目标 70%/核心 100%、禁止依赖外部环境
4. **安全规约**：水平权限校验、敏感数据脱敏、SQL 注入/CSRF/XSS/重定向白名单、防重放、文件上传校验、配置密码加密等（10 强制 + 1 推荐）
5. **MySQL 数据库**：建表（is_xxx 布尔、三字段 id/create_time/update_time、逻辑删除、decimal 禁 float/double）、索引（唯一索引必建、三表 join 限制、varchar 索引长度、 cấm 左模糊）、SQL（count(*)/ISNULL/分页 count=0 短路、禁止外键/存储过程）、ORM（禁止 select *、resultMap 映射、#{} 防注入）
6. **工程结构**：应用分层（开放 API / 终端显示 / Web / Service / Manager / DAO / 第三方 / 外部数据）、分层异常处理、领域模型（DO/DTO/VO/BO/POJO）规约
7. **设计规约**：面向对象、设计原则等（详见 PDF 与 `coding-guidelines-summary.md`）

黄山版新增 11 条新规约，版本号 1.7.1。

> 详细条目速查见 `coding-guidelines-summary.md`；完整原文以 PDF 为准。

## 在本项目（gate）中的定位

本项目为 **Local Git Commit Gate**，技术基线见 `docs/architecture/production-architecture.md`（Java 17、无 JGit、Spring 仅在 gate-adapters/gate-cli、真实 git 二进制等）。

- **强制条目** — 默认纳入 Code Review 阻断项；与架构基线冲突时以 `production-architecture.md`、`adr/*`、`governance.md`、`capability-registry.md` 为准，并通过 ADR/豁免登记（`exemption-register.md`）而非静默绕过。
- **推荐/参考条目** — 作为评审建议；新增能力/模块需在 `docs/capabilities/*` 与 `docs/architecture/ownership-catalog.md` 中明确 owner 后落地。
- **工具链** — 推荐通过 `p3c-pmd` + IDEA 插件在本地与 CI 中执行，配置见 `p3c-integration.md`。本项目 Maven 父 POM `pom.xml:1` 已约束 Java 17，接入时需选择与 PMD/Java 17 兼容的 p3c-pmd 版本。

## 快速开始

1. 精读 `coding-guidelines-summary.md`（约 15 分钟速查）。
2. 需要条文原文时打开 `Java开发手册(黄山版).pdf`（全文 51 页正文 + 目录/附录）。
3. 本地开发安装 IDEA 插件：`Settings → Plugins → Marketplace → 搜索 "Alibaba Java Coding Guidelines"`，重启后 `Tools → 阿里编码规约扫描`。
4. CI/本地校验接入见 `p3c-integration.md`。

## 溯源与更新

- 上游：`https://github.com/alibaba/p3c`，分支 `master`，文件 `Java开发手册(黄山版).pdf`。
- 本地更新：重新执行 `curl.exe -L -o docs/standards/alibaba-java-coding-guidelines/Java开发手册(黄山版).pdf "https://raw.githubusercontent.com/alibaba/p3c/master/Java%E5%BC%80%E5%8F%91%E6%89%8B%E5%86%8C(%E9%BB%84%E5%B1%B1%E7%89%88).pdf"` 并同步更新本 README 的版本与校验信息。
- 许可证：Apache License 2.0（见上游 `license.txt`），本地镜像仅作参考资料分发，不修改原文内容。

## 相关文档

- `docs/README.md` — 文档中心入口与治理规则
- `docs/architecture/governance.md` — 架构适应度与复杂度治理
- `docs/architecture/production-architecture.md` — 重构实施基线
- `docs/adr/README.md` — 架构决策记录
