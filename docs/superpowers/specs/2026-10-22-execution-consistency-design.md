# 执行一致性、状态与资源规则（审查修订）

日期：2026-10-22。状态：根据用户逐项审查修订的规范，Java部分已于2026-10-25随实现验收；独立Agent按其已批准的实施计划继续。此规范补充两项总体设计和多轮对话设计；此前“切换一律取消任务”“全部缓存由 Compose 控制”等宽泛表述以本文为准。

## 1. 标识、版本与任务归属

| 字段 | 唯一职责 | 何时变化 |
|---|---|---|
| conversation_id | 一次聊天会话，等同审查意见中的 session_id | 新建聊天；与登录 Cookie session ID 区分 |
| topic_id | 会话内逻辑话题 | 新建话题生成；恢复 A 仍使用 A 的 id |
| generation_epoch | 当前前台交互版本，数据库 BIGINT，单调递增 | 真正新消息、有效停止/切换、人工接管、关闭；幂等重试不增加 |
| run_id | 一次逻辑 Agent 指令执行 | 新指令/显式恢复生成；broker 重投和同任务故障恢复不另建 run |
| client_message_id | 用户消息网络幂等键 | 前端一次用户发送生成一次，重试复用 |
| jobs.execution_version | Worker 领取令牌，即 fencing token / lease_token 的等价实现 | 每次成功领取/重新领取严格增加；续约不增加 |
| outbox.lease_version | 一次发布权的版本，不能与任务执行权混用 | 发布者领取/重新领取增加 |
| 授权版本（Java复用users.session_version；独立Agent用其自身auth_version） | 用户权限版本，不是授权通行证 | 禁用、角色或授权范围改变时事务内增加 |
| task_revision | 独立后台 Agent 任务的指令版本 | 显式修改该任务才增加；切换前台话题不增加 |

A→B→A 的 topic_id 可以相同，generation_epoch 必须不同。消息、topic、run 与 job 均有 tenant_id 复合外键；topic 必须属于同一 conversation。worker 的 run_id 不能替代 execution_version；同一 run 的旧 Worker 也必须被拒绝。

| 工作归属 | 当前范围 | 完成条件 | 前台切话题的影响 |
|---|---|---|---|
| INTERACTIVE_TOPIC | Java 客服前台回答 | 当前 epoch/topic/run、合法租约、授权与预算均有效 | 取代旧回答，停止其后续动作 |
| BACKGROUND_DOMAIN | Java 知识导入 | 文档版本/导入任务、合法租约、授权与预算有效 | 不影响；结果属于文档版本 |
| BACKGROUND_TOPIC | 独立 InsightPilot 报告 | 固定 topic/run/task_revision、合法租约、授权、查询预算有效 | 不取消，不要求 topic 当前活跃；完成后只写回所属话题 |

ServiceHub 第一版不增加后台报告业务或第四个聊天控制接口。InsightPilot 后台任务使用自己的显式任务取消/修改 API，具体接口在其实施计划中定义。取消当前回答与取消指定后台 run 是两个动作；不能用 topic 不活跃推导后台任务已取消。授权撤销、显式取消、任务修改、过期等仍会使后台提交失败。

## 2. 消息幂等与 epoch 的原子事务

唯一约束为 `UNIQUE(tenant_id, conversation_id, client_message_id)`；同时保留 `UNIQUE(tenant_id, conversation_id, generation_epoch)` 的前台逻辑 run 约束、`UNIQUE(tenant_id, kind, business_key)` 的 job 约束及一 run 一正式回答约束。相同 key 不同 sender 或规范化正文返回 409 idempotency_conflict。

发送顺序固定如下：

1. 执行现有身份、CSRF、请求校验及限流。租户来自服务端会话，不能由 body 覆盖。
2. 开启短 MySQL 事务；按第 5 节顺序锁授权行、会话行，查同一消息 key。规范化正文沿原实现为 strip 后比较，不做语义相似去重；摘要仅加速比较，仍校验正文及 sender。
3. 已存在时不更新会话、不再取消任务、不写 outbox，返回同一 message_id、原 accepted_generation_epoch、原 trigger_run_id / trigger_job_id；原任务可能已经完成或被取代，不改成当前新任务。元数据可以反映原消息已解析的 topic，但不重新执行。权限已失效仍拒绝，幂等不能绕过身份或限流。
4. 不存在时，在锁内以当前 e 更新 epoch 为 e+1，并增加 conversation.version；将旧前台 run/job 变为不可执行。插入消息、AI run、job、outbox 与持久会话事件，同一事务提交。人工回复或无 AI 执行的消息，其 trigger_run_id / trigger_job_id 为 null。
5. 事务提交后返回并通知投递；RabbitMQ 暂时不可用不回滚已成功接受的消息，Outbox 可恢复。提交前崩溃全部回滚，提交后响应丢失可由同一 key 重试取得原结果。

MessageDTO 在旧字段之外增加 accepted_generation_epoch、可空 trigger_run_id / trigger_job_id；原 ai_run_id 仍表示 AI 作者消息的来源，不能改变原字段含义。这些关联存入消息行或不可变关联，不能临时按“当前 run”推算。

数据库唯一约束是最后保护，不依靠单 JVM if 或内存锁。所有写路径必须遵循同一事务；唯一键冲突回读已提交原记录并进行内容比较。topic/停止使用 expected_generation_epoch CAS；同一旧版本两个切换至多一个成功，其余 409，不静默覆盖。相同 actor+目标 epoch 的停止回执可重放，但不能再次推进 epoch 或取消后来产生的新 run。

## 3. 状态转换表

### 3.1 topic 与会话

会话状态继续沿用 AI_ACTIVE / WAITING_HUMAN / HUMAN_ACTIVE / CLOSED。conversations.active_topic_id 是当前话题的权威指针；TopicDTO 的 ACTIVE/SUSPENDED 根据指针与关闭状态导出，不维护另一套可能分叉的 active 布尔值。

| topic 当前状态 | 触发 | 下一状态 | 事务条件/结果 |
|---|---|---|---|
| ACTIVE A | 切到新/旧 B | A SUSPENDED，B ACTIVE | expected_epoch 命中，指针/epoch/事件同事务 |
| SUSPENDED A | 显式恢复 A | A ACTIVE | 会话未关闭、属于本人授权会话；新前台 run；无 pending 问题则只切换 |
| ACTIVE/SUSPENDED | 会话关闭 | COMPLETED | 禁止新前台执行，已保存内容只读；不把独立后台任务默认为取消 |
| 任意 | 过期 CAS、越权、跨会话 topic | 不变 | 409 或原权限错误，无副作用 |

后台任务可以向其固定的 SUSPENDED/已归档 topic 保存报告，不能设置 active_topic_id、推进前台 epoch 或生成当前回答。显式删除/撤销访问属于新的授权变化，阻止未提交结果。

### 3.2 前台 run

| 当前状态 | 触发 | 下一状态 | job / lease 行为 |
|---|---|---|---|
| QUEUED | 获得执行资源及有效租约 | RUNNING | 第一次开始时固定 deadline，attempts +1 |
| RUNNING | 话题/指代需用户明确选择 | WAITING_INPUT | 保存待确认摘要，job WAITING_INPUT，释放 lease/资源并 ack |
| WAITING_INPUT | 用户明确解析 pending 问题 | SUPERSEDED，另建新 run QUEUED | 原确认不复用，新 run 有新 epoch 和新截止时间 |
| WAITING_INPUT | 新指令取代/显式取消/超过 24 小时 | SUPERSEDED / CANCELLED / EXPIRED | 无自动执行，不保留运行资源 |
| RUNNING | 可重试故障且尝试/预算未耗尽 | QUEUED | 保存下一 outbox 与 available_at 后 ack；截止时间不重置 |
| RUNNING | 成功且所有 fence 有效 | SUCCEEDED | 回答+事件+job SUCCEEDED 同事务 |
| QUEUED/RUNNING | 新消息/有效切换取代 | SUPERSEDED | 终止该前台执行，旧 lease 不可提交 |
| QUEUED/RUNNING | 明确停止/权限撤销 | CANCELLED | 终态、不自动重试 |
| QUEUED/RUNNING | 队列/运行预算过期 | TIMED_OUT | 明确 QUEUE_TIMEOUT / DEADLINE_EXCEEDED，不伪造正常回答 |
| RUNNING | 永久错误或第 3 次失败 | FAILED | 保存真实错误、失败事件；不能成功 ack 后丢状态 |
| 任意终态 | 重投/迟到结果 | 不变 | 安全 ack / 丢弃，不新建正式回答 |

WAITING_INPUT 不是可无限续约的运行状态。恢复是新指令执行，不给原 run 延长 120 秒。HTTP 旧状态映射固定：QUEUED→queued、RUNNING→running、RETRY_WAIT→retrying、SUCCEEDED→succeeded、FAILED/TIMED_OUT→failed、CANCELLED/SUPERSEDED→cancelled；超时原因放 error_code。WAITING_INPUT→waiting_input 为明确新增状态，前端/Schema 同步扩展；EXPIRED→failed 并附 input_expired。内部状态不能直接将旧字段改成大写枚举。显式管理员 retry 是 FAILED→QUEUED 的受控例外，条件见下文。

### 3.3 job 与 lease

| job 当前状态 | 触发与条件 | 下一状态 | token / attempts |
|---|---|---|---|
| QUEUED/RETRY_WAIT | available_at 已到，未超时且获资源 | RUNNING | 数据库条件领取；execution_version +1，attempts +1，owner/lease_expires_at 写入 |
| RUNNING | 原 owner/token 且租约尚未过期 | RUNNING | 每 10 秒续到数据库 now+60 秒，token 不变 |
| RUNNING | 租约过期 | RETRY_WAIT 或 FAILED/TIMED_OUT | 回收前锁领域/run/job，按尝试和预算判断；旧 token 从此不可用 |
| RUNNING | 可重试故障 | RETRY_WAIT | 下一 outbox 与状态同事务；首次失败后 5 秒，第二次失败后 30 秒 |
| RUNNING | 成功/永久失败/取消/等待输入 | SUCCEEDED/FAILED/CANCELLED/WAITING_INPUT | 提交后清 owner/lease，消息 ack |
| FAILED | 管理员明确重试且允许 | RETRY_WAIT | manual_retries +1，当前批 attempts 归零、保留 lifetime_attempts；显式新预算批次、重新授权，下一领取 token 必须增加 |
| CANCELLED/WAITING_INPUT/SUCCEEDED | 普通重投或自动重试 | 不变 | 不消耗一次执行尝试，不能复活 |

自动尝试是每批总共最多 3 次，包含初始执行；租约过期导致的重领也消耗下一次尝试。broker 重投但未成功 claim 不增加 attempts。权限拒绝、明确取消、非法参数、维度不匹配等永久错误不自动重试；网络故障/429/供应商 5xx 可在剩余总预算内重试。数据库短暂故障无法落库时不能 ack 原消息。

已核对原 Python JobService.retry：管理员可重试 failed job，返回同一 job_id。保留此接口：导入任务按当前文档权限/版本检查；前台 failed run 仅在它仍是当前 epoch/topic、会话 AI_ACTIVE、原用户 auth_version 有效且从未取消/被取代时允许。保留 job_id/run_id，记录显式 manual_retries 和新的 budget_revision，重新给予一批预算；下一次 claim 增加 token。旧执行不能利用人工重试的新预算。已经切换/取消/取代的前台任务返回 409 job_conflict，不能复活。该 409 是新增安全约束的显式兼容差异，契约报告必须保留并说明。用户发新指令仍另建 run 与 epoch。

## 4. Outbox、Consumer 幂等与数据库 fencing

业务写入、run、job 和 outbox 原子提交。Outbox 的 PENDING → PUBLISHING → DISPATCHED 分别代表待发、带 owner/version/过期时间的发布权、得到 confirm 且未被 return 的成功投递；nack/return/confirm 超时回到可重发状态。标记 DISPATCHED 必须 CAS 同一 outbox lease_version，旧发布者的迟到 confirm 不能覆盖新发布者。先发布后标记，允许重复消息；不先标记后发布。

消息只传 job_id 与 schema_version，不能由消息体注入租户/权限或修改业务 payload。消费端从数据库读取权威任务并条件领取。其他 owner 正持有效 lease 时，重复 delivery 可以 ack，由租约回收保证后续投递；不得创建第二个逻辑 job。终态重复安全 ack；坏协议拒绝进死信。唯一键和任务状态确保逻辑幂等，不承诺供应商只调用一次。

正式结果写事务在完成所有领域/权限校验后，至少包含下列条件更新，affected_rows 必须为 1；随后与结果/事件同事务提交。为 0 则整个事务回滚，不能先保存消息再在事务外检查：

```sql
UPDATE jobs SET state='SUCCEEDED', owner=NULL, lease_expires_at=NULL
WHERE id=:job_id AND tenant_id=:tenant_id AND state='RUNNING'
  AND owner=:owner AND execution_version=:token
  AND lease_expires_at > UTC_TIMESTAMP(6);
```

前台另在同一事务锁定会话/run 并校验 AI_ACTIVE、generation_epoch、active_topic_id、当前 run 与 deadline；独立后台校验固定领域版本或 task_revision，不能误用当前前台 epoch。人工重试/恢复必须先取得更高 token。租约续约基于数据库时间，Redis 只协调资源/取消通知，不是业务提交依据。

旧 Worker A(token=1) 过期后，新 Worker B(token=2) 合法完成；A 恢复时上述 CAS 为 0，消息、报告、checkpoint、工具写操作均回滚/拒绝。测试必须覆盖“token 未变但租约已过期，尚未被 B 领取”的窗口，同样拒绝 A。

## 5. 动态授权、TOCTOU 与写操作边界

Actor增加捕获的authVersion；Java将其映射到已存在的users.session_version，角色/启用状态变化时递增，不新增第二个同步计数器；会话凭据、run/checkpoint 中保存它只用于检测变化，不视为已授权结论。恢复前重新加载身份/权限。成员禁用、降权/授权范围变化和 auth_version +1 在同一事务；Redis 失效通知只能加速。

写入事务首先按 user_id 升序锁参与授权的 users 行：普通提交 FOR SHARE，成员变更对目标 FOR UPDATE；管理员同时锁 actor/target 时同样排序。随后按“会话/topic → 业务资源（稳定类型+id 顺序）→ run → job → outbox”取得必要锁；只领取 job 的操作不在持有 job 锁后反向取得领域锁。事务不跨网络调用；瞬态死锁最多重放 2 次纯数据库事务，不能重放已经发生的外部动作。

提交业务变更时在同一事务检查用户 enabled/role/auth_version、对象归属和资源版本。权限行共享锁与管理员的排他修改使两者串行：撤销先提交则旧写拒绝；合法业务先锁定提交后撤销才能提交，不把撤销前已完成的合法写误判为越权。不能用事务外 `if (allowed)` 代替这个边界。

检索/模型开始前、每个工具前、敏感结果返回前都复核；模型最终回答、工具创建草稿、知识版本激活、checkpoint/报告保存也执行上述授权事务。文档行的 enabled/active_version 复核与最终引用提交在同一锁范围内。权限版本变化时旧 run 明确终止，不根据旧 checkpoint 自动接受新权限。

已发送的网络数据无法收回；撤销后的下一受保护操作必须拒绝。没有数据库授权依据时失败关闭，不能靠 Redis 缓存角色继续写。真实外部不可逆操作须有独立幂等键、结果台账/未知状态和补偿或人工处理；本期不增加支付/退款/发货等写工具，取消不充当回滚或补偿。

售后草稿记录origin_topic_id/origin_run_id/origin_epoch及origin_budget_revision/action_hash（人工创建可空）作为来源；同一run和预算批次的相同准备动作由唯一键复用草稿，避免Worker恢复重复写草稿；跨话题不会自动迁移或自动确认。客户显式打开有效草稿时仍按 draft_id/version/expires_at/订单归属和 CSRF 校验办理，保持原 6 个接口。当前 ServiceHub 没有额外“业务确认令牌”，不能为泛化设计假造已实现机制。InsightPilot 的 HITL 确认绑定 topic/run/task_revision/SQL hash/auth_version/预算摘要，条件变化即失效，不得通过新消息隐式恢复旧确认。

## 6. 资源隔离与硬截止时间

Java 总执行并发 4、BACKGROUND 最多 3，INTERACTIVE 保证至少 1 个可用执行名额；空闲前台可使用其他空闲名额。模型 HTTP 总并发 4、BACKGROUND 最多 3，同时在一个原子 Lua 中核验总数和分类计数。文档 Embedding 也属于 BACKGROUND，不得绕过供应商名额。多Worker共用项目级owner+到期的资源记录，不能每进程各自称“全局4”。这里限制的是有效准入与应用受控请求；租约失效也无法强制终止供应商已开始的计算，物理重复/费用仍须如实报告。InsightPilot 初期总并发 2、后台最多 1，其独立部署限制单独记录，不宣称这就是百炼账号总配额。

执行与模型两层都隔离；只设 RabbitMQ 优先级不能提供上述保证。交互/后台独立消费通道和有界等待队列，后台预取不得占用前台 listener；无资源时不 claim、不增加 attempts、不忙循环重投，使用至多 1 秒的可取消资源等待或持久延后投递。投递、续租、资源续期、回收、取消信号分别使用独立有界调度，不能排在模型线程池之后。

| 预算 | 开始与范围 | 到期行为 |
|---|---|---|
| 前台排队最长 60 秒 | 从消息事务提交 accepted_at 到首次领取；包括无执行资源等待 | QUEUE_TIMEOUT，零供应商调用 |
| 前台执行硬截止 120 秒 | 从首次成功领取 started_at 固定 deadline_at；包含分类、执行后排队、模型槽等待、工具、网络、供应商重试、退避与恢复 | DEADLINE_EXCEEDED；取消订阅、阻止新动作和提交 |
| 单次供应商调用最长 45 秒 | timeout=min(45 秒, remaining)；不在重试时重新给予整轮预算 | 可重试错误或总预算终止 |
| Java 导入排队/执行 | 排队 600 秒，首次开始后执行 600 秒；不套用聊天 120 秒 | 明确失败/超时，不影响已激活旧文档 |
| 等待用户选择 | 无执行 lease/模型槽，最长保留 24 小时 | 原 run EXPIRED，用户重新提问 |

120 秒是应用提交截止，不保证外部已发请求在同一瞬间停止计算。DB 持久 deadline 为权威；本地倒计时用从 DB 计算的 remaining 与单调时钟，不依赖宿主时区/时钟回拨。重启、重复投递、重领、重试都不延长截止。调用数/工具数先在合法执行版本下持久预留，崩溃后不重置预算；预留未实际发出也保守计入次数，实际 usage 仍以供应商返回为准。

三次是最大尝试数，剩余 120 秒不足退避或调用时可以早于第三次终止。等待人工选择后显式解析问题创建新 run，不能以暂停为由给旧执行无限续时。前台保留名额只避免后台挤占，不承诺多名前台同时请求或供应商限流时零排队；在线延迟另行实测。

## 7. 本地/Docker/Maven 边界

项目直接管理的源码、工具、构建缓存、测试输出和持久化数据留在 R；源码工作树 W 本身是 R 的子目录。Docker Desktop 的全局镜像、层、构建缓存、容器可写层和引擎/WSL 虚拟磁盘由 Docker 管理，不因 Compose 位于 R 就全部迁入 R；不为本迁移改变全局 Docker 设置。容器内 Maven 的 /workspace/.cache 是隔离构建路径，其物理层同样属于引擎存储，必须在交付说明中列出例外。

本地脚本明确设置 `MAVEN_USER_HOME=R/.cache/maven`；所有 Maven 调用显式传递 `-Dmaven.repo.local=R/.cache/m2`。`.mvn/settings.xml` 只作一致性配置，不能因 cwd 切换失效。wrapper 使用项目锁定的 distributionUrl/checksum；在根目录、backend-java 目录及不同工作树启动均验证实际 Maven 分发路径和 localRepository，没有偷偷回到 C:/Users/.../.m2。

Git 和 Docker 构建上下文排除 `.cache/`、`.tools/`、`.data/`、`.venv/`、`.env*`（明确的示例除外）、Git 元数据和真实上传。Java 多阶段镜像只 COPY pom、wrapper 与 src，构建缓存使用显式引擎缓存标识且不写入凭证。数据库/Redis/MQ 的持久目录 bind mount 到 R/.data/java，不使用未说明的匿名持久卷。Testcontainers 的临时容器层属于引擎例外，日志/报告导出 R/.cache/java-verification。

已有 worktree 先检查真实绝对路径位于 R、Git common-dir、对应分支/HEAD、是否被其他会话使用及工作区状态；存在未提交改动不覆盖、不 reset、不强制复用，不删除无关目录。Compose 发布端口显式 `127.0.0.1:host:container`；启动前查占用，测试端口由 Testcontainers 动态分配后获取真实 host/mappedPort。测试 API JVM 仍只绑定回环，不能猜测端口。

## 8. 时间与金额的精确转换

DATETIME(6) 不携带时区。应用 Instant → `LocalDateTime.ofInstant(value, UTC)` → JDBC DATETIME；读取 `getObject(..., LocalDateTime.class).toInstant(UTC)`，由统一 MyBatis TypeHandler 完成，不依赖默认时区。持久化前明确截断到微秒。JDBC 设 connectionTimeZone=UTC、forceConnectionTimeZoneToSession=true、preserveInstants=true，连接初始化确认 session time_zone 为 +00:00；驱动参数不能替代显式 DATETIME 转换。数据库租约比较使用 UTC_TIMESTAMP(6)。

已用现有OrderDTO离线合成样本核实JSON时间：零微秒为`2026-10-22T00:00:00Z`，非零为固定六位，例如`.123000Z`/`.123456Z`。Java按此格式与可空规则序列化，不能默认截短成三位或换成+00:00；测试用同一 Instant 在 JVM UTC、Asia/Shanghai、America/New_York 下往返，并覆盖夏令时边界，不能改全局主机时区来迁就测试。

已核实现有 orders.total_amount 和 order_items.unit_price 是 DECIMAL(12,2)。Java 继续 BigDecimal；存储输入要求 scale<=2，超精度/超范围拒绝，不让 MySQL 静默截断/舍入。显式统计需要舍入时统一 HALF_UP 两位并在指标中说明，第一版不凭空增加退款金额计算。现有 Pydantic DTO 金额 JSON 为字符串，如 "12.30"；Java 用 toPlainString 的定点字符串，禁止二进制浮点与科学计数法。计数、ID 仍是原整数类型。

## 9. 官方依据（仅支持机制，不是本项目通过证明）

- [RabbitMQ Reliability](https://www.rabbitmq.com/docs/reliability)：确认与消费责任、重复投递；Outbox 和业务 fencing 是本项目额外实现。
- [Maven Wrapper](https://maven.apache.org/tools/wrapper/)：MAVEN_USER_HOME 控制分发缓存基址；依赖库另行配置。
- [Docker Desktop Settings](https://docs.docker.com/desktop/settings-and-maintenance/settings/) 与 [Port publishing](https://docs.docker.com/engine/network/port-publishing/)：引擎存储边界和显式主机 IP。
- [Git worktree](https://git-scm.com/docs/git-worktree) 与 [Testcontainers Networking](https://java.testcontainers.org/features/networking/)：工作树状态与映射端口。
- [MySQL Connector/J time instants](https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html)：DATETIME 与 Instant 不天然等价，需明确转换。
