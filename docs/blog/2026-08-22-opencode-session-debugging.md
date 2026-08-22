# 发个"你好"过去，一天就没了

周末折腾自己的小项目 Gate（一个本地 Git 门禁工作台，简单说就是：建工单 → 给工单切一个沙箱克隆 → 让 AI agent 在克隆里干活 → 走审查流程再合并）。这天测会话功能：T-106 工单上选好 OpenCode 的 agent，输入框里敲了句"你好"，回车。

然后呢？没有然后。没有回复，没有报错，什么都没有。

拿另一个工具看 opencode 自己那边的会话记录——空的。0 条消息，token 全是 0。

行吧，那就查吧。本来以为半小时的事，结果这一天就这么搭进去了。把过程记下来，坑还挺典型的。

## 三层剥洋葱

查这种问题的套路就是从数据往上走，看断在哪层。

第一层，Gate 自己的库。拿 token 直接调接口，会话在、状态 ACTIVE，消息列表里有我那句"你好"，还多了一条 ERROR。ERROR 内容长这样：

```json
{"name":"UnknownError","data":{"message":"Unexpected server error...","ref":"err_825428d0"}}
```

这个 JSON 格式不是 Gate 吐的，是 **opencode 的错误格式**。有意思了——请求到了 opencode，被它拒了。范围缩小。

第二层，opencode 的日志。拿着 `err_825428d0` 去 `~/.local/share/opencode/log/opencode.log` 里一搜：

```
level=ERROR ref=err_825428d0 error="Error: default agent \"sisyphus\" not found"
```

默认 agent 叫 `sisyphus`，不存在，直接 500。

第三层，为什么配置会指向一个不存在的 agent？打开 `~/.config/opencode/opencode.jsonc`，末尾赫然写着 `"default_agent": "sisyphus"`。而 sisyphus 的定义在同目录的 `oh-my-openagent.jsonc` 里——一个**没有任何东西加载的文件**。不在引用链上，不在 plugin 列表里，node_modules 里也没这玩意。真正生效的自定义 agent 只有 `agents/` 目录下四个 markdown 文件，拿运行中 serve 的 `/agent` 接口一对，确实没有 sisyphus。

大概是之前某个版本这套配置还是好的（老会话记录里 agent 就叫 "Sisyphus - ultraworker"），升级或者清理的时候注册丢了，`default_agent` 这行却留下来了。于是所有走默认 agent 的请求全部爆炸。

顺手还抓到个帮倒忙的：前端收到 SSE 的 error 事件时只是把流停了，啥也不显示。所以用户视角才是"毫无反应"。改，两处一起。

## 把代码导进权威库，又是一个坑

agent 的问题修完了（default_agent 改成 build），该解决空仓库了，不然 agent 上班没有活干。

目标很朴素：把 dsh-ha-orchestrator 的工作区推到 auth.git 里。结果 `rev-parse --is-bare-repository` 一跑，返回 false；列目录一看，里面孤零零躺着一个 `.git` 子目录。

好家伙，当初不知道谁手滑把它建成普通仓库了，工作区就是这个空目录本身。而系统代码期望的是 bare 仓库——门禁钩子要装在 `hooks/` 下，非 bare 布局下装错位置形同虚设，往检出的 main 上 push 还会被拒。

手术步骤：

```
1. auth.git\.git 里的内容整体上移一层，删掉 .git 子目录
2. git config core.bare true
3. 工作区 force push 到 main
4. 再补 receive.denyDeletes / denyNonFastForwards 那套配置
5. 存量克隆 fetch + reset 同步
```

这里有个顺序坑：`denyNonFastForwards=true` 是连 force push 一起拦的。所以收紧配置得放在 force push **之后**，不然第一步就把自己堵死。

## 能通了，然后第二句必挂

修完兴冲冲测试。第一句正常回复，美滋滋。第二句——气泡里先把第一句的回答复述一遍，然后无限转圈。

两个 bug 叠一起了。

第一个：适配器调的是 opencode 的同步接口 `/session/{id}/message`，整轮跑完才返回。HttpClient 超时设了 30 秒，deepseek 思考加调工具早就超了。更气人的是客户端这边已经报错判死，服务端那边还在吭哧吭哧正常干活——我在另一个工具里看着内容实时往外蹦，Gate 里已经放弃了。

第二个是个"复读机"bug：SSE 连接建立时会先回放一遍历史消息，前端把回放里的旧回复覆盖到了新气泡上。所以看起来像是把上一句的回答又说了一遍。

当时先止血：超时拉到 10 分钟，回放帧忽略。但知道这是治标。

## 去读别人的源码

带着"别人是怎么封装 opencode 的"去啃了两个项目的源码，收获很大。

**openchamber**（GitHub 上九千多星那个）的做法：opencode 其实提供三类接口——同步的 `/message`、**立即返回的 `prompt_async`**、还有一条 `GET /event` 的 SSE 事件总线。总线上的事件粒度细到 `text.delta`、`reasoning.delta`、每个工具调用的开始/入参/成功/失败、会话的 busy/idle 状态。openchamber 在服务端常驻一个 reader 挂总线上，断线用 Last-Event-ID 续传，静默太久强制重连，然后 WebSocket 扇出给浏览器。发消息只 fire 不等。

**ai-toolbox**（我自己在用的会话管理工具）走了另一条路：不连总线，直接只读打开 opencode 的 SQLite 数据库，前端轮询重读快照。所谓实时其实是刷出来的。

结论没啥悬念，抄 openchamber。而且运气不错，我这边的地基本来就铺了一半——流式块的定义、前端 SSE 的事件分发都是现成的，缺的只是把"同步等响应"改成"异步触发 + 挂总线"。

重构过程的小插曲也记一笔：Java 17 没有 pattern switch（preview 特性），编译器教做人；测试里的假 HTTP server 忘了设线程池，阻塞式的 SSE handler 把其他请求全堵死了，测试莫名跑满 15 秒才挂；还有 PowerShell 5.1 居然没有三元运算符，一行脚本语法报错导致前面写的 Stop-Process 根本没执行，我还纳闷孤儿进程怎么杀不死——这个是后话了。

## 复读机第二集：原来它会把我的话再说一遍

流式上线之后出现新花样：每次回复开头都会把我说的话重复一遍。

又抓了一次原始事件流，这次看清了一个关键规律。opencode 收到用户消息后会把它作为普通的 text part 在总线上**回显**出来，而且每条消息的角色声明（`message.updated` 带 role）总是先于这条消息的内容部件到达。

之前的过滤只看 sessionID——用户消息和助手消息当然同属一个 sessionID，于是我的"hi"被当成助手的增量转发给了前端。修复很简单：维护两个已见消息 ID 的集合，已知是用户消息的部件直接跳过。

## 思考链外泄

下一个问题出在一次带工具的任务上：界面吐出一大坨英文思考过程，还拆成了好几条气泡。

继续抓包，这次看清了多步任务的真实形态。opencode 对 agentic 回合是**每一步产生一条独立的 assistant message**，大概长这样：

```
msg_1: step-start | text("The user is asking if I know...") | tool | step-finish
msg_2: step-start | text("Let me look for the ticket info...") | tool | step-finish
msg_3: step-start | text("The directory is local-run/clones/T-106") | tool | step-finish
```

注意这些思考叙述是以 **text part** 的形式来的（我这个模型/网关不分离 reasoning 字段），不是 reasoning part。而我之前每完成一条消息就落库一条，于是一次任务变成 N 条气泡的思考流水账，刷新后工具调用卡片还全丢了。

解法还是抄 openchamber 的呈现思路：回合内所有步骤先攒着，等会话回到 idle 状态一次性落库为**一条回复**——正文拼接、tool_calls 打包、各步的 token 加在一起。这里还埋了个特别阴的竞态：回合状态的清零逻辑原来写在发送请求返回之后，而事件可能在请求落地几毫秒内就涌进来了，清零直接把刚到的数据抹掉。挪到发送之前就好了。

## 最隐蔽的一个：僵尸服务器

以为完事了，结果三个归档会话全失败。一个 ApiError，两个 UnknownError。

ApiError 那个好解释：agent 配置里 model 留空，回落到默认 agent 自带的模型，被 opencode 官方网关地区封锁了（"This model is not available in your country."）。

两个 UnknownError 才是真的精彩。日志显示又是 `default agent "sisyphus" not found`——可我明明几小时前就把全局配置改掉了啊？！对着进程列表看了半天，恍然大悟：

```
25476  13:41 启动  serve --port 49153   ← 重启前的老进程
18584  13:41 启动  serve --port 49154   ← 同上
39412  14:59 启动  serve --port 49152   ← 重启后的新进程，配置正常
```

后端重启会把端口分配器的内存状态清掉，但老的 opencode serve 进程还活着占着端口。重启之后分配器把同样的端口号又发了出去，新 spawn 的进程绑定失败秒退，而健康检查那边——**僵尸服务器抢答了**。新会话就这样神不知鬼不觉地挂在了配置过期好几个小时的旧进程上。

那 shutdown hook 为什么没兜住？hook 其实写了，但 Windows 上直接叉掉 cmd 窗口，JVM 经常不给机会执行就直接没了。

补了四道防线：杀掉现存孤儿；spawn 之前先探测端口，已有东西应答就明确报错拒绝复用；spawn 时把 PID 记到文件里，下次启动自动清扫（校验进程的可执行路径包含 opencode 才动手，防止 PID 被回收复用时误杀）；归档和删除会话无条件释放进程资源——之前归档居然只是改了个数据库字段，serve 进程永远留在那。

## 最后承认一下：日志欠的债

复盘的时候不得不承认，这次查得这么累，很大程度上是因为自己埋的雷：整个项目只有一处 LoggerFactory 还没绑定实现（等于没有），适配器代码里到处是 `catch (Exception ignored)`。

补了个极简的 JSONL 日志，写到 gate-home 目录下，从进程 spawn、端口拒绝、上游连接断线卡死，到消息落库、回合失败全程留痕。另外把"回合以错误结束且什么都没产出"的情况也持久化下来——以前这种失败只活在实时推送里，页面一刷新就尸骨无存，想查只能去翻第三方日志。

## 随便总结两句

按糟心程度排的话：

外部 CLI 这种依赖真的要多防着点。它的配置体系、进程生命周期、版本行为全都不归你管，今天默认 agent 消失，明天网关地区封锁，后天升级丢注册——集成的那一刻就该假设它会以各种姿势坏掉。

凡是可能跑超过半分钟的操作，别用同步调用赌时长。异步加事件订阅应该是一开始就定的方案，30 秒超时和 10 分钟超时本质上都是在赌，只是赌注大小不同。

子进程不会跟着父进程一起死，Windows 上尤其如此。内存里的端口分配状态和操作系统的真实占用一定会漂移，要么登记 PID 主动清扫，要么启动时预检拒绝，二选一总比静默错配强。

错误信息不能只推一次就完事。任何不落库的错误，对刷新过页面的人来说就是不存在的。

以及最重要的：别猜，去抓数据。这次所有关键结论——错误格式、事件到达顺序、每步一条独立消息、僵尸服务器——全是靠读数据库、抓 SSE 帧、列进程实锤的。凭感觉猜浪费的时间比验证贵多了。

还没做完的：会话内切换模型和推理强度（opencode 支持，需要给系统加接口和 UI）；思考过程的文本现在还是会显示在回复正文里，想彻底折叠得靠模型侧把 reasoning 分离出来。
