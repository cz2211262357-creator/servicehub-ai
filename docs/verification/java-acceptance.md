# Java 迁移验收记录

本文件区分已有证据、尚在合并回归的结果和真实供应商验收。当前处于 Task14交付阶段；本机部署与修复回归已通过，公开 GitHub CI 单列，独立 InsightPilot Agent 尚未实现。

## 一致性与状态依据

执行规则以 [执行一致性设计](../superpowers/specs/2026-10-22-execution-consistency-design.md) 为准。该文档包含 topic/run/job/lease 状态、线性化点、授权锁序和故障处置；代码与测试不能用 Redis 的临时通知替代 MySQL 状态。

- 消息接受事务包含唯一消息、epoch、run、job、事件和 Outbox。相同 key、sender 与规范化正文复用原始 receipt；内容冲突409，不再次推进 epoch。
- 前台停止与有效切换使用 expected_generation_epoch 的 CAS。A→B→A 的旧 A 仍属于过期 epoch；停止回执重放不取消之后的新 run。
- RabbitMQ confirm 与 return 共同决定发送结果；允许重投。任务租约使用 jobs.execution_version，发布租约使用 outbox_events.lease_version；最终 SQL 条件验证 owner、token、未过期租约、状态、预算版本与业务范围。
- 前台交互排队最多60秒，首次领取后固定120秒；后台知识导入排队与执行各600秒。单调用取45秒与剩余硬预算的较小值；自动总尝试3次、退避5/30秒；不重置已预留的6次模型与4次工具预算。
- 总执行和模型槽各4，后台分别最多3；独立消费者、续约、恢复、发布与索引清理线程。后台导入绑定文档版本和修订，不依赖当前前台 epoch。
- 写事务持有当前授权行锁并复核 auth_version；模型/检索/工具前再次验证。恢复状态不携带可复用的“已授权”结论。停用文档或切换生效版本后，旧证据不能提交。
- Agent 的写工具只创建草稿；正式售后申请由客户本人确认。当前没有扣款、实际退款或发货工具；将来接入不可逆外部操作必须使用独立业务幂等键、提交前授权、人工批准和补偿/人工处置。停止聊天不承诺撤回已经完成的外部操作，也不撤销已经确认的工单。

## 可执行入口

Windows 在源码工作树中运行；R 是用户指定的项目根目录，ReportPath 必须位于 R/.cache/java-verification。

~~~powershell
./scripts/java-system-tests.ps1 -WorkspaceRoot R -ReportPath R/.cache/java-verification/system.json
./scripts/java-online-check.ps1 -WorkspaceRoot R -ReportPath R/.cache/java-verification/online.json
./scripts/java-benchmark.ps1 -WorkspaceRoot R -ReportPath R/.cache/java-verification/benchmark.json
~~~

系统入口运行全部42个 HTTP、WebSocket、真实多进程竞争及构建边界测试，失败返回非零。在线入口是独立 opt-in 程序：凭据仅从忽略配置读入，不接受密钥参数，不在普通 Maven/CI 中调用供应商。压测入口对并发1/10/20各执行100次普通 HTTP 请求；模型延迟单列，不能据此推算生产容量。

## 当前已观察到的证据

2026-10-25 完整原生回归通过：34项单元测试、299项集成测试，失败/错误/跳过均为0，代码提交 b450531。其后8项真实数据库移交验收通过，包括真实COMMIT应答丢失与提交后任务取消；修复定向回归34单元+46集成、商家订单35单元+8集成、真实Compose Redis启动35单元+1集成分别通过。这些切片重叠，不能累加为当前完整测试数。实际部署和独立审查已通过，当前源码的完整公开CI仍待执行。此前定向结果保留在私有日志中，不重复累加为全量计数。

| 范围 | 已有证据 |
|---|---|
| HTTP/WS | FullHttpContractIT82：42项运行时场景、39项匿名保护、1项真实 WebSocket 升级/回放；对照独立 Python FastAPI/SQLAlchemy 和 Java HTTP，使用不同真实数据库与 Redis |
| 消息与 epoch | 双实际 API JVM，20轮；每轮20个同时重复消息加10次重试，消息/run/job各1，receipt一致、epoch只增长1 |
| 回滚与冲突 | 20轮；MySQL消息插入、任务插入的真实触发器故障，确认命中后验证完整回滚；不同内容复用 key 返回409 |
| 切换与停止 | 20轮；双页面 CAS、A→B→A、旧停止回执和迟到旧提交 |
| 确认与抢单 | 20轮；每轮20次客户确认、20名不同客服在两个 API 上竞争；工单和赢家各1 |
| 撤权优先 | 20轮真实供应商替身 HTTP 阻塞，管理员先撤销，再放行模型；新回答/草稿0 |
| 业务优先 | 20轮；业务 SQL 命名锁阻塞点、独立事务的真实 NOWAIT3572、撤权 HTTP 等待；业务先201、撤销后200、后续写401 |
| 发布故障 | 实际 TCP 代理丢弃 broker basic.ack confirm 帧，另暂停/恢复 RabbitMQ；Outbox不提前标记，重复投递仍1个逻辑任务/正式结果 |
| Worker恢复 | 实际强杀/重启；另暂停旧 Worker、使数据库租约过期、由替代 Worker 以更高 token 完成，再恢复旧进程；实际观察 RabbitMQ 心跳剔除暂停连接后计量替补接管，连续重复3次，旧写均0 |
| 依赖与重试 | 暂停 MySQL 时 broker保留待处置消息；持续503实测3次及5/30秒间隔；永久400仅1次；无伪造成功 |
| 资源隔离 | 3个后台真实 embedding HTTP 阻塞；前台实际 chat 与执行各在1秒内获得名额；总活跃≤4、后台≤3；切换后后台仍完成 |
| 构建边界 | 3种 cwd 实测 Maven 分发/仓库/临时目录；真实 Docker 镜像8个私有哨兵均被排除，6个公开端口均绑定127.0.0.1 |
| 镜像 | Java API和React/Nginx均已真实构建；后续已重建含审查修复的产品镜像，运行中API与Worker配置摘要均为sha256:0df395e7a17f5070a931afb84dd2e3c8f5d3e4a6674569db7c9211e7a33c3589，不再使用前一版镜像 |
| 请求体错误 | 10类长度/版本边界已与旧接口一致；额外8类非法JSON/缺失/null/枚举/查询类型已全部与旧实现一致，不回显被拒绝值 |

详细注入和通过条件见 [A01–A20](java-concurrency-acceptance.md)，字段差异见 [接口契约矩阵](java-contract-matrix.md)。D01–D06 单列，不修改冻结 Python 基线来隐藏差异。

## 在线验收与报告规则

在线入口检查真实百炼 qwen-plus 和 text-embedding-v4。先进行小规模响应格式探测，再运行文本 PDF→1024维 FLOAT32向量→激活→中文引用回答、订单工具、售后草稿、客户确认、人工接管，以及三种真实流式打断和15条路由开发样本。

报告保留实际 response.model、供应商返回的 usage 或 null、排队/首分片/完成/本地取消延迟、旧结果数、期望/实际路由与分类请求计数。普通追问不得调用分类模型；需要分类的样本必须完成真实供应商请求，不能把失败降级当作模型评测成功。15条样本只表示开发验收覆盖，不能写成通用意图准确率。

2026-10-25真实百炼闭环已通过，实际响应模型为 qwen-plus/text-embedding-v4；所有所需步骤完成。三个完整问答的本地端到端时间为3.58、3.69、5.99秒，usage分别659、1637、1913tokens。三种真实流式打断的本地取消请求耗时约80、132、165毫秒，旧正式回答均为0。15条开发路由样本全部匹配，只有3条模糊指代发起一次分类，缓存复用不再请求。这些数值是小样本观察，不能写成通用准确率或延迟SLA。

普通HTTP压测每档100次，并发1/10/20的P95约18.6/35.0/25.0毫秒，错误0。数据量较小且包含连接预热差异，不据此推算部署容量或模型吞吐。脱敏结果见 [机器汇总](java-acceptance-summary.json)。

真实工具流暴露了兼容问题：续片id为空、完整工具调用以stop结束。保留实际脱敏分片为回归样本；适配仅忽略空续片元数据，最终标识/工具白名单/参数/预算/授权/租约及人工确认仍然强制验证。更改标识、缺失标识、空工具名、畸形JSON和未结束流均被拒绝。

## 路径、缓存与容器边界

R内管理源码、JDK、uv虚拟环境、Maven分发/依赖、Node依赖、日志、测试输出和持久数据。Wrapper通过项目脚本设置 MAVEN_USER_HOME、MAVEN_BASEDIR、maven.repo.local 和 UTF8/临时目录；不改用户全局 Maven 配置。

Docker镜像层、BuildKit缓存及引擎运行时由现有 Docker Desktop 管理，这是单独列明的引擎存储边界。Compose只指定 R内的业务持久目录；不会为移动引擎存储而修改全局 Docker配置。Dockerfile按固定摘要构建，应用以UID10001运行；Linux绑定上传目录需预先赋予该UID写入权限。

.cache、.tools、.data、.superpowers、.env、构建产物及 node_modules 不进入 Git 或 Docker上下文。构建测试记录自建容器 ID，仅启动/删除带本次 fixture标记的容器，不执行全局 prune、重置其他工作树或清理原 Python 服务。

## Task14交付复核

已关闭整分支审查中的上传/移交COMMIT不确定性、同话题订单追问、迟到HTTP快照、multipart错误码、上传路径一致性与系统故障正向对照问题。Ctrl+C/CancelledError在发送COMMIT前即受到文件保留保护。已明确选择的话题不重复分类。

干净部署发现并修复了Compose shell绕过Redis官方模块加载的问题；ComposeRedisIT实际启动配置中的命令，验证FT命令可用。普通商家订单编号无需ORD测试前缀，仍由对象白名单及当前所有权控制。

本机六服务启动并经实际流程验证，全部端口仅绑定127.0.0.1；10账号通过Nginx入口登录验证。两份公开合成政策重新向量化成功，真实百炼完成带引用问答、商家订单查询与不重复订单号的售后草稿追问。未执行正式工单确认，旧Python库计数不变。验收前逐字节核对文件和全部订单均对应既有公开虚构seed；历史聊天保持EXCLUDED，不向供应商发送。

最终公开版本及CI地址将在通过后登记于交付证据。
