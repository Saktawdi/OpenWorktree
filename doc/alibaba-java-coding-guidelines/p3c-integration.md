# p3c-pmd / IDE 插件 接入指南（适配 gate 多模块 Maven）

> 目标：在本项目（`gate-parent` / Java 17 / `pom.xml:1`）中落地黄山版规约的自动化检查，不引入 JGit，不破坏 `gate-domain/ports/application` 零 Spring 约束。

## 1. 插件与版本选择

| 组件 | 推荐版本 | 说明 | 来源 |
| --- | --- | --- | --- |
| `p3c-pmd` | `2.1.1`（对应 PMD 6.x，兼容 Java 17） | 49 条 PMD 规则实现，黄山版需 ≥2.0.1 | [alibaba/p3c/p3c-pmd](https://github.com/alibaba/p3c/tree/master/p3c-pmd) |
| IDEA 插件 | Marketplace 最新 `Alibaba Java Coding Guidelines` | 4 条 IDE 侧规则（`@Override`/`deprecated`/`静态成员类名访问`/`equals-hashCode`） | IDEA Marketplace |
| PMD Maven 插件 | `maven-pmd-plugin 3.20+` | 用于 `mvn pmd:check` | Maven Central |
| 别名 | `Alibaba Java Coding Guidelines` 亦可在 `Alibaba Cloud Toolkit` 中集成 | 云效已集成扫描引擎 | — |

> 若使用 PMD 7，需选用 `p3c-pmd` 的 PMD7 分支；本项目当前使用 `maven-pmd-plugin` 3.x（PMD6）最稳妥。

## 2. IDE 侧（开发者本地，必装）

1. `Settings → Plugins → Marketplace → 搜索 "Alibaba Java Coding Guidelines" → Install → Restart`
2. `Tools → 阿里编码规约扫描 → 打开/关闭实时检测`；或右键项目/文件 `编码规约扫描`
3. 规则级别：默认 `Blocker/Critical/Major` 三档；建议将 `Blocker` 设为本地提交前必清零
4. 快捷键：`Ctrl+Shift+Alt+P`（Win）触发扫描；结果面板双击定位

> 已验证：本项目 `.idea/` 存在，团队可通过 `Settings → Editor → Code Style → Scheme → Import` 统一导入 `p3c-formatter`（仓库 `p3c-formatter` 目录）提供的 Eclipse/IDEA 格式化配置。

## 3. Maven 侧（CI/本地命令行，推荐）

### 3.1 父 POM 引入（`pom.xml`）

在 `gate-parent/pom.xml` 的 `<properties>` 增：

```xml
<p3c-pmd.version>2.1.1</p3c-pmd.version>
<pmd.plugin.version>3.20.0</pmd.plugin.version>
```

在 `<build><plugins>` 增（仅在 `verify` 阶段执行，不影响 `gate-domain/ports/application` 零 Spring 约束）：

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-pmd-plugin</artifactId>
  <version>${pmd.plugin.version}</version>
  <configuration>
    <rulesets>
      <!-- p3c 自带规则集 -->
      <ruleset>rulesets/java/ali-comment.xml</ruleset>
      <ruleset>rulesets/java/ali-concurrent.xml</ruleset>
      <ruleset>rulesets/java/ali-constant.xml</ruleset>
      <ruleset>rulesets/java/ali-exception.xml</ruleset>
      <ruleset>rulesets/java/ali-flowcontrol.xml</ruleset>
      <ruleset>rulesets/java/ali-naming.xml</ruleset>
      <ruleset>rulesets/java/ali-oop.xml</ruleset>
      <ruleset>rulesets/java/ali-orm.xml</ruleset>
      <ruleset>rulesets/java/ali-other.xml</ruleset>
      <ruleset>rulesets/java/ali-set.xml</ruleset>
    </rulesets>
    <printFailingErrors>true</printFailingErrors>
    <failurePriority>2</failurePriority> <!-- 1=Blocker, 2=Critical, 3=Major -->
    <excludeRoots>
      <excludeRoot>target/generated-sources</excludeRoot>
    </excludeRoots>
  </configuration>
  <dependencies>
    <dependency>
      <groupId>com.alibaba.p3c</groupId>
      <artifactId>p3c-pmd</artifactId>
      <version>${p3c-pmd.version}</version>
    </dependency>
  </dependencies>
  <executions>
    <execution>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

> 也可拆为 `pmd:check` 手动执行：`mvn -pl gate-domain,gate-application pmd:check -Dpmd.failurePriority=2`

### 3.2 单模块跳过（如生成代码）

```xml
<properties><pmd.skip>true</pmd.skip></properties>
```

### 3.3 与 `verify-fast` / `verify-governance` 的关系

- `docs/tools/verify-fast.ps1` 与 `docs/tools/verify-governance.ps1` 为本项目架构门禁；
- p3c 扫描作为 **新增门禁**，建议在 `verify-governance.ps1` 末尾追加 `mvn pmd:check` 调用，或在 CI 中单独 job `p3c`，与现有门禁并行。

## 4. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Unsupported Java version` | PMD 版本与 Java 17 不匹配 | 升级 `p3c-pmd` 至 2.1.1+，或降 `maven-pmd-plugin` 至 3.20 |
| `rule ali-xxx not found` | `rulesets` 路径错误 | 使用 `rulesets/java/ali-*.xml`（p3c-pmd jar 内） |
| `gate-domain` 误报 Spring 依赖 | 误将 Spring 接入纯 domain | 检查 `p3c-pmd` 仅为 `provided` 扫描依赖，不引入 `spring-boot-starter` |
| 与 `spotless/checkstyle` 冲突 | 格式化规则不一致 | 以 `p3c-formatter` 为主，`checkstyle` 规则对齐黄山版 |

## 5. 团队约定

- **提交前**：IDE 插件扫描 `Blocker` 清零；`mvn pmd:check` 本地通过（`failurePriority=2`）。
- **CI 上**：`pmd:check` 失败阻断合并；存量债务登记至 `docs/debt-register.md` 与 `docs/architecture/exemption-register.md` 并设到期时间。
- **豁免**：确需豁免时在代码加 `// NOPMD` 需附理由，或在 `rulesets` 中 `exclude`，并同步登记豁免。

## 6. 参考

- 上游：`https://github.com/alibaba/p3c`，`p3c-pmd/README.md`、`p3c-formatter`、`idea-plugin`、`eclipse-plugin`
- 本地：`docs/standards/alibaba-java-coding-guidelines/README.md`、`coding-guidelines-summary.md`、`Java开发手册(黄山版).pdf`
