# 阿里巴巴 Java 开发手册（黄山版）精简速查

> 版本：黄山版 1.7.1 / 2022-02-03 / 55 页。约束分级：【强制】>【推荐】>【参考】。本文件为速查提炼，条文原文以同目录 `Java开发手册(黄山版).pdf` 为准。页码指 PDF 内页（1/51 为正文第 1 页）。

## 阅读建议

- **Code Review 阻断**：所有【强制】视为缺陷；【推荐】未遵守需在 PR 说明理由。
- **与本项目基线冲突**：以 `docs/architecture/production-architecture.md`、`docs/adr/*`、`docs/architecture/governance.md` 为准，需登记 ADR/豁免。
- **工具**：`p3c-pmd` 覆盖 49 条 PMD 规则 + IDE 插件 4 条（@Override / 弃用 API / 静态成员类名访问 / equals-hashCode 成对重写），配置见 `p3c-integration.md`。

---

## 一、编程规约（P1–23）

### (一) 命名风格 P1–3

- 【强制】命名禁止 `_`/`$` 开头或结尾。
- 【强制】严禁拼音+英文混合或中文命名；`ali/alibaba/taobao/aliyun/youku` 等国际通用词视为英文。
- 【强制】禁用种族/侮辱性词汇；用 `blockList/allowList/secondary` 替代 `blackList/whiteList/slave`。
- 【强制】类名 `UpperCamelCase`，例外 `DO/PO/DTO/BO/VO/UID`；如 `UserDO/HtmlDTO`。
- 【强制】方法/参数/成员/局部变量 `lowerCamelCase`。
- 【强制】常量 `UPPER_SNAKE`，语义完整：`MAX_STOCK_COUNT`。
- 【强制】抽象类 `Abstract/Base` 开头，异常 `Exception` 结尾，测试类 `被测类名+Test`。
- 【强制】数组定义 `int[] arrayDemo`，禁止 `String args[]`。
- 【强制】POJO 布尔字段禁止 `is` 前缀（序列化框架误判 `deleted`），DB `is_xxx` 需在 `resultMap` 映射。
- 【强制】包名全小写、单数、`点` 间一单词：`com.alibaba.ei.kunlun.aap.util`，类名可用复数。
- 【强制】避免子父类/同方法不同块 同名变量；setter/getter 外参数禁止与成员同名。
- 【强制】杜绝不规范缩写：`AbsClass/condi/Fu`。
- 【推荐】用完整单词组合自解释：`AtomicReferenceFieldUpdater`。
- 【推荐】类型名词放词尾：`startTime/workQueue/nameList/TERMINATED_THREAD_COUNT`。
- 【推荐】使用设计模式时体现在命名：`OrderFactory/LoginProxy/ResourceObserver`。
- 【推荐】接口方法/属性不加修饰符（不写 `public abstract`），加 Javadoc；接口常量需谨慎。
- 【强制】Service/DAO 暴露为接口，实现加 `Impl` 后缀：`CacheServiceImpl/CacheService`。
- 【推荐】能力接口用形容词 `-able`：`Translatable`。
- 【参考】枚举类 `Enum` 后缀，成员全大写下划线：`ProcessStatusEnum.SUCCESS`。
- 【参考】Service/DAO 方法：`get` 单个、`list` 多个复数、`count` 统计、`save/insert` 新增、`remove/delete` 删除、`update` 修改；模型：`xxxDO/xxxDTO/xxxVO`，禁止 `xxxPOJO`。

### (二) 常量定义 P3–4

- 【强制】禁止魔法值直接出现，必须预定义常量。
- 【强制】`long/Long` 后缀大写 `L`；浮点后缀大写 `D/F`。
- 【推荐】按功能分类常量，不用单一 `Constants` 大类。
- 【推荐】五层复用：跨应用（二方库 `constant`）、应用内（一/子模块 `constant`）、包内、类内。
- 【推荐】枚举优于常量类，字段私有不可变。

### (三) 代码格式 P4–5

- 【强制】大括号 `K&R`，首行不换行，`else/catch/finally` 与右括号同行；`}` 后空行（方法/类/控制块）。
- 【强制】单行不超过 120 字符，方法参数/二元操作符换行时缩进 4 空格。
- 【推荐】IDE 保存自动格式化，统一 `.editorconfig`。

### (四) OOP 规约 P6–8

- 【强制】`equals` 重写必重写 `hashCode`；`Set` 判重、`Map` 键均依赖二者。
- 【强制】重写方法必须加 `@Override`（区分 `getObject/get0bject` 拼写陷阱）。
- 【强制】静态成员通过类名访问，禁止实例访问。
- 【强制】禁止使用过时 API，需替换为新接口。
- 【强制】POJO 必须重写 `toString`（便于日志），使用 `Objects.equals/hash` 等工具需注意 NPE。
- 【推荐】构造器参数少于 5 个，超限用 Builder。
- 【推荐】类成员顺序：静态变量 → 实例变量 → 构造器 → 方法。

### (五) 日期时间 P9–10

- 【强制】禁止使用 `SimpleDateFormat` 非线程安全实例共享；用 `DateTimeFormatter`/`LocalDate(Time)` 或加锁/ThreadLocal。
- 【强制】日期格式化统一 `yyyy-MM-dd HH:mm:ss`、GMT；前后端统一。
- 【推荐】获取当前时间用 `Instant.now/System.currentTimeMillis`，避免 `new Date()` 歧义。

### (六) 集合处理 P10–13

- 【强制】`equals/hashCode` 成对重写后才可作 `Set/Map` 键；`String` 已重写可直接作键。
- 【强制】`ArrayList.subList` 结果不可强转 `ArrayList`，修改原集合影响子列表。
- 【强制】`Arrays.asList` 返回定长列表，禁止 `add/remove`，需可变用 `new ArrayList<>(Arrays.asList(...))`。
- 【强制】集合判空用 `isEmpty()` 而非 `size()==0`。
- 【强制】使用 `entrySet` 遍历 `Map`，避免 `keySet` 再 `get`。
- 【强制】`Collections` 工具返回不可变集合时勿修改。
- 【推荐】初始化集合指定容量：`new HashMap<>(expectedSize/0.75+1)` / `new ArrayList<>(size)`。
- 【推荐】利用 `Stream` 但避免过度链式导致可读性下降。

### (七) 并发处理 P14–17

- 【强制】线程池禁止用 `Executors` 快捷方法，必须 `ThreadPoolExecutor` 显式参数（队列、拒绝策略、线程工厂命名）。
- 【强制】`SimpleDateFormat` 非线程安全（见上）；`CountDownLatch/CyclicBarrier/Semaphore` 需明确超时。
- 【强制】并发 `equals` 判断禁作退出条件，用区间判断防击穿：`stock<=0` 而非 `stock==0`。
- 【强制】`synchronized` 锁对象稳定，不用 `String/Integer` 包装类作锁。
- 【推荐】高并发计数用 `LongAdder/AtomicLong` 优于 `synchronized`。
- 【推荐】创建线程/线程池命名，便于排查：`ThreadFactoryBuilder`。
- 【参考】`volatile` 不保证原子性，复合操作需加锁/原子类。

### (八) 控制语句 P17–20

- 【强制】`switch` 每个 `case` 需 `break/return`，`default` 必写；`String` switch 注意 NPE。
- 【强制】`if/else/for/while/do` 必须用大括号，即使单行。
- 【强制】三目运算符注意自动拆箱 NPE：`flag ? a*b : c` 当 `a*b` 为 `int` 会拆箱 `c(null)`。
- 【强制】高并发场景禁 `==` 作中断条件。
- 【推荐】方法 >10 行时 `return/throw` 右括号后空一行。
- 【推荐】卫语句替代深层 `if-else`（不超过 3 层），策略/状态模式重构。
- 【推荐】条件中不做复杂表达式，赋给布尔变量：`boolean existed = ...; if(existed)`。
- 【推荐】不 在条件表达式中赋值。
- 【推荐】循环体内不定义对象/连接/`try-catch`，移至循环外。
- 【推荐】不用取反逻辑：`x<628` 优于 `!(x>=628)`。
- 【推荐】公开/批量接口做入参保护（数量、范围）。
- 【参考】参数校验：低频/开销大/高稳定/开放接口/敏感入口 必须校验；高频底层/private 可省略（文档注明）。

### (九) 注释规约 P20–21

- 【强制】类/属性/方法用 `/** */` Javadoc，不用 `//`；抽象方法必须写做什么、返回值、参数、异常、实现要求。
- 【强制】类必加 `@author`/`@date`（`yyyy/MM/dd`）。
- 【强制】方法内单行注释在语句上方 `//`，多行 `/* */` 并对齐。
- 【强制】枚举字段必注释用途。
- 【推荐】中文注释优于蹩脚英文，专有名词保留英文。
- 【推荐】代码改注释同步改；删除未用字段/方法/参数。
- 【参考】谨慎注释掉代码，需说明原因 `/// 业务方通知暂停`，无用则删（Git 可追溯）；`TODO/FIXME` 标注人/时间/预期。

### (十) 前后端规约 P21–23

- 【强制】API 明确协议(HTTPS)/域名/路径(名词复数、小写下划线、无 `.json` 后缀)/方法(GET/POST/PUT/DELETE)/请求体(Content-Type)/状态码/响应体。
- 【强制】列表为空返回 `[]`/`{}`，不返回 `null`。
- 【强制】错误响应含 `HTTP 状态码 + errorCode + errorMessage + userTip`；`errorMessage` 可落 hidden 控件/日志，勿含敏感信息。
- 【强制】JSON key `lowerCamelCase`：`errorCode/assetStatus/orderList`。
- 【强制】超大整数用 `String` 返回，禁 `Long`（JS `Number` 精度仅 2^53）。
- 【强制】URL 参数 ≤2048 字节；body 长度受 `nginx 1MB / tomcat 2MB` 限制。
- 【强制】分页：前端输入 `<1` 归一，后端输入 `>总页` 返回末页；内部重定向用 `forward`，外部重定向走统一代理。
- 【推荐】返回头标记缓存 `Cache-Control: s-maxage=...`；数据用 JSON 非 XML；时间统一 `yyyy-MM-dd HH:mm:ss` GMT。
- 【参考】接口路径不带版本号，版本放 Header。

### (十一) 其他 P23

- 【强制】正则预编译：`Pattern.compile` 复用，禁方法内重复编译。
- 【强制】禁 `Apache BeanUtils`（性能差），可用 `Spring BeanUtils/Cglib BeanCopier`（浅拷贝注意）。
- 【强制】`velocity` 取 POJO 属性直接 `$!{var}`（`!` 防 null 露出），`boolean` 自动调 `isXxx`，`Boolean` 调 `getXxx`。
- 【强制】`Math.random()` 返回 `double [0,1)`，取整用 `Random.nextInt/nextLong`。
- 【强制】`enum` 字段私有不可变。
- 【推荐】视图模板不写复杂逻辑；数据结构初始化指定大小；及时清理垃圾代码（注释掉用 `///` 说明）。

---

## 二、异常日志（P24–28）

### (一) 错误码 P24

- 【强制】原则：快速溯源、沟通标准化；回答“谁的错/错在哪”。
- 【强制】错误码字符串 5 位：`来源(1)+编号(4)`；`00000` 表示成功。
- 来源：`A` 用户、`B` 系统、`C` 第三方；编号 0001–9999，大类步长 100（见附表 3）。
- 【强制】不体现版本/等级；不与业务/组织架构挂钩，先到先得、审批固化。
- 【强制】复用附表错误码，勿随意新增；错误码不直接展示给用户（需转 `userTip`）。
- 【推荐】业务细节放 `error_message`；第三方 `C` 转 `B` 时带原错误码。
- 【参考】宏观码：`A0001/B0001/C0001`；后三位与 HTTP 状态码无关。

### (二) 异常处理 P25–26

- 【强制】可预检查的 `RuntimeException`（`NPE/IndexOutOfBounds`）用 `if` 规避，禁 `try-catch` 当流程控制。
- 【强制】区分稳定/非稳定代码，细分异常类型处理；勿大段 `try-catch`。
- 【强制】捕获后必须处理或抛出，禁止吞异常；最外层转用户可理解文案。
- 【强制】事务中 `catch` 后需手动回滚。
- 【强制】`finally` 关闭资源（JDK7+ 用 `try-with-resources`）；`finally` 禁 `return`（覆盖 `try return`）。
- 【强制】捕获异常与抛出异常类型匹配（父类可接）；RPC/反射/二方包调用捕获用 `Throwable`（防 `NoSuchMethodError`）。
- 【推荐】返回值可为 `null`，但必须注释何时返回 `null`；防御 NPE 六场景（拆箱/DB null/集合元素 null/远程调用/Session/级联 `a.getB().getC()`，可用 `Optional`）。
- 【推荐】区分 `checked/unchecked`，自定义业务异常（`DAOException/ServiceException`），禁直接抛 `RuntimeException/Exception/Throwable`。
- 【参考】对外 HTTP/API 必用错误码；内部推荐抛异常；跨应用 RPC 优先 `Result`（`isSuccess/errorCode/errorMessage`）。

### (三) 日志规约 P26–28

- 【强制】依赖 SLF4J/JCL 门面，禁直接用 Log4j/Logback API：`LoggerFactory.getLogger(X.class)`。
- 【强制】日志至少保留 15 天；当天 `/{dir}/{app}/logs/{app}.log`，历史 `{app}.log.yyyy-MM-dd`；合规类日志 ≥6 个月多机备份。
- 【强制】扩展日志命名 `appName_logType_logName.log`（`stats/monitor/access`），错误/业务分文件。
- 【强制】拼接用占位符：`logger.debug("id:{} symbol:{}", id, symbol)`。
- 【强制】`trace/debug/info` 输出前判级别：`if(logger.isDebugEnabled())`。
- 【强制】`additivity=false` 防重复打印；生产禁 `System.out/err` 与 `e.printStackTrace()`。
- 【强制】异常日志含案发现场参数 + 堆栈 `logger.error("params:{} msg:{}", toString(), e.getMessage(), e)`；日志中禁 `JSON.toString(obj)`（getter 抛异常会阻断主流程，仅打印业务字段/toString）。
- 【推荐】生产禁 `debug`、按需 `info`、`warn` 记录用户参数错误（勿频繁 `error` 误报警）；英文描述优先，国际化/海外全英文。
- 【推荐】敏感信息脱敏，用订单号/UUID 追踪。

---

## 三、单元测试（P29–30）

- 【强制】AIR：`Automatic` 自动化、`Independent` 独立性、`Repeatable` 可重复。
- 【强制】全自动非交互、`assert` 验证，禁 `System.out` 人肉验证。
- 【强制】用例间不互相调用、不依赖执行顺序。
- 【强制】不依赖外部（网络/服务/中间件）；SUT 依赖改为注入，测试注入本地/Mock。
- 【强制】粒度 ≤类、一般方法级；只测本单元，不测跨类/跨系统。
- 【强制】核心业务/应用/模块增量代码必须单测通过；代码在 `src/test/java`，不与业务代码混放。
- 【推荐】语句覆盖率 ≥70%，核心模块 语句+分支 100%（DAO/Manager/高复用 Service 必测）。
- 【推荐】BCDE：`Border` 边界、`Correct` 正例、`Design` 结合设计、`Error` 异常/非法输入。
- 【推荐】DB 测试用程序插入数据，不假设数据已存在；可回滚或加前缀 `FOUNDATION_UNIT_TEST_`。
- 【推荐】不可测代码重构；设计评审时与测试确定范围；提测前完成单测。
- 【参考】避免：构造器做太多、过多全局/静态、过多外部依赖、过多条件分支（用卫语句/策略/状态重构）；破除四误解（测试的事/多余/不维护/与线上无关）。

---

## 四、安全规约（P31）

1. 【强制】用户个人页面/功能必须水平权限校验（禁越权访问/篡改他人数据）。
2. 【强制】敏感数据脱敏：手机号 `139****1219`。
3. 【强制】SQL 参数绑定/METADATA 限定，禁字符串拼接；转义 `#--` 等危险字符。
4. 【强制】所有入参有效性校验：防 `pageSize` OOM、`order by` 慢查询、缓存击穿、SSRF、重定向、SQL/Shell/反序列化注入、ReDoS（正则特殊串死循环）。
5. 【强制】输出到 HTML 的用户数据必须安全过滤/转义，防 XSS。
6. 【强制】表单/AJAX 必做 CSRF 校验。
7. 【强制】外部重定向目标白名单过滤，防钓鱼。
8. 【强制】短信/邮件/下单/支付等平台资源加防重放：限量/疲劳度/验证码。
9. 【强制】文件上传校验大小/类型，防恶意文件远程执行。
10. 【强制】配置文件密码加密。
11. 【推荐】发帖/评论等 UGC 加防刷与违禁词风控。

---

## 五、MySQL 数据库（P32–36）

### (一) 建表规约 P32–33

- 【强制】是否字段 `is_xxx` + `unsigned tinyint(1/0)`；POJO 布尔无 `is`，`resultMap` 映射。
- 【强制】库/表/字段小写字母/数字，禁数字开头、禁 `__` 夹数字；MySQL Win 不区分大小写、Linux 区分，统一小写。
- 【强制】表名单数，不用复数。
- 【强制】禁保留字 `desc/range/match/delayed`。
- 【强制】主键 `pk_字段`、唯一 `uk_字段`、普通 `idx_字段`。
- 【强制】小数 `decimal`，禁 `float/double`（精度损失）；等长串用 `char`，`varchar` ≤5000，超长用 `text` 单表主键关联。
- 【强制】必备三字段：`id bigint unsigned PK 自增1`、`create_time datetime`、`update_time datetime`（需时区用 `timestamp`）。
- 【强制】逻辑删除禁物理删除；唯一键需处理逻辑删除后不唯一问题。
- 【推荐】表名 `业务_作用`：`alipay_task/force_project`；库名≈应用名；字段含义变及时更新注释。
- 【推荐】适当冗余提性能（非频繁修改、非唯一、非超长 `varchar/text`），如冗余商品名避免调 IC 服务。
- 【推荐】单表 >500 万行或 >2GB 再分库分表。
- 【参考】按类型选长度：`tinyint 1B 0-255`（人 150 岁）、`smallint 2B 0-65535`（龟）、`int 4B 0-43亿`（恐龙）、`bigint 8B`（太阳 50亿年）。

### (二) 索引规约 P33–34

- 【强制】唯一特性字段必建唯一索引（即使应用层已校验，墨菲定律必出脏数据）。
- 【强制】>3 表禁止 `join`；`join` 字段类型一致且有索引。
- 【强制】`varchar` 索引指定长度，按区分度 `count(distinct left(col,n))/count(*)`，长度 20 时区分度常 >90%。
- 【强制】禁左模糊/全模糊搜索，走搜索引擎；利用 B-Tree 最左前缀。
- 【推荐】`order by` 利用索引有序：`where a=? and b=? order by c` → `a_b_c`；范围查询后索引有序失效 `where a>10 order by b` 无法用 `a_b`。
- 【推荐】覆盖索引避免回表（`explain extra: using index`）；超大分页用延迟关联 `SELECT t1.* FROM t1,(select id from t1 where ... limit 100000,20) t2 where t1.id=t2.id`。
- 【推荐】SQL 优化目标 `range → ref → const`（`const` 主键/唯一单行；`ref` 普通索引；`range` 范围；`index` 全索引扫描最差）。
- 【推荐】组合索引区分度高者在左；等号条件前置：`where c>? and d=?` 建 `idx_d_c`。
- 【推荐】防隐式类型转换致索引失效。
- 【参考】三误解：宁滥勿缺 / 吝啬建索引 / 抵制唯一索引（先查后插）。

### (三) SQL 语句 P34–35

- 【强制】`count(*)` 标准统计行，禁 `count(列/常量)`（后者不计 NULL）；`count(distinct col)` 不计 NULL；`count(col)` 全 NULL 返回 0，`sum(col)` 全 NULL 返回 NULL 需 `IFNULL(SUM(col),0)`；`ISNULL(col)` 判空（`NULL=NULL` 为 NULL）。
- 【强制】分页 `count==0` 直接返回，不执行后续分页。
- 【强制】禁外键与级联（分布式/高并发下强阻塞、更新风暴、拖慢插入），应用层保证。
- 【强制】禁存储过程（难调试/扩展/移植）。
- 【强制】订正数据先 `select` 确认再 `update/delete`。
- 【强制】多表查询/变更 列名前加 `表别名.` 限定时，避免 `Column ambiguous`（曾因新增同名字段线上 1052）。
- 【推荐】别名加 `as` 且 `t1/t2/t3` 顺序命名。
- 【推荐】`in` 集合 <1000，超限分批。
- 【参考】字符集用 `utf8mb4`（表情），`LENGTH("轻松工作")=12` vs `CHARACTER_LENGTH=4`；`TRUNCATE` 快但无事务不触发 trigger，代码中慎用。

### (四) ORM 映射 P35–36

- 【强制】查询禁 `select *`，显式列名（省解析/网络/`resultMap` 不一致/`text` 浪费）。
- 【强制】POJO 布尔无 `is`，DB 有 `is_`，`resultMap` 映射。
- 【强制】禁 `resultClass`，必定义 `<resultMap>`（解耦）。
- 【强制】参数用 `#{}`，禁 `${}`（SQL 注入）。
- 【强制】禁 `queryForList(statement,start,size)`（先全量再 `subList` OOM），用 `map.put("start/size")` 分页。
- 【强制】禁 `HashMap/Hashtable` 接结果集（`bigint` 在不同 DB 版本解析为 `Long` vs `BigInteger`）。
- 【强制】更新必同步 `update_time=now()`。
- 【推荐】不写大而全 `update`（`set c1,c2,c3` 全量更新易错、低效、binlog 膨胀），只更新变化字段。
- 【参考】`@Transactional` 不滥用，考虑缓存/搜索/消息回滚；`<isEqual>/<isNotEmpty>/<isNotNull>` 语义区分。

### (五) 工程结构（P37–40）

- 【推荐】分层（上可依赖下）：开放 API（RPC/HTTP/网关）→ 终端显示（velocity/JS/JSP/移动）→ Web（转发/参数校验）→ Service（业务）→ Manager（通用：第三方封装/缓存/中间件/多 DAO 组合）→ DAO（MySQL/Oracle/HBase/OceanBase）→ 第三方服务/外部数据接口。
- 【参考】分层异常：DAO `catch(Exception) throw new DAOException` 不打日志；Service 必须打日志带参数上下文；Manager 同 DAO（同机）或同 Service（单部署）；Web 不向上抛，转友好页；开放 API 转错误码。
- 【参考】模型：`DO=表结构`（DAO 向上）、`DTO=传输`（Service/Manager 向外）、`VO=展示`、`BO=业务对象`，统称 `POJO` 禁 `xxxPOJO`。

---

## 六、设计规约（对照 PDF 第 6–7 章，概要）

- 面向对象六原则（SOLID+迪米特等）、类/接口设计、异常/日志/事务边界均遵循上述各章；新增设计需在 `docs/architecture/*` 与 ADR 中明确。

---

## 七、本项目落地要点

| 维度 | 本项目强约束 | 说明 |
| --- | --- | --- |
| 命名 | 采用手册命名 | `DO/DTO/VO/BO` 严格区分；`isDeleted` 等布尔禁 `is` 前缀，DB `is_deleted` |
| 常量 | 禁魔法值 | 缓存 key 前缀（如 `Id#taobao_`）抽 `CacheConsts`；`long` 用 `L` |
| 并发 | 线程池显式化 | 本项目 `gate-*` 模块若涉及并发，禁止 `Executors` 快捷，需命名线程工厂 |
| 异常 | 5 位错误码 | 对外 API 错误码 `A/B/C+4位`，内部抛业务异常，日志含参数+堆栈 |
| 日志 | SLF4J 占位符 | 本项目 `gate-bootstrap/gate-adapters` 日志配置 `additivity=false` |
| DB | 按手册建表 | `id/create_time/update_time` 三字段、`decimal`、逻辑删除、以 `docs/architecture/ownership-catalog.md` 为 owner |
| 测试 | AIR/BCDE | 覆盖率目标写入 `verification-contract.md`，`src/test/java` 独立 |

---

## 附：错误码附表 3（概要）

- `A` 用户错误（参数、版本、支付超时等）、`B` 系统错误（业务逻辑、健壮性）、`C` 第三方（CDN、消息投递等），四位编号大类步长 100，`A0001/B0001/C0001` 为一级宏观码。

> 完整附表与示例见 PDF 附录。
