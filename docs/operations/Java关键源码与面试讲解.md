# ServiceHub Java 关键源码与面试讲解

本项目把电商售后咨询、知识问答、人工接待与工单确认连接起来。Java 后端负责业务与模型接入，React 提供客户、客服、管理员三个入口。模型只允许检索政策、查询本人订单和准备售后草稿；正式工单由客户确认后创建。订单使用合成资料，模型、数据库、向量索引与队列均走真实实现。

## 先阅读哪些代码

| 入口 | 阅读目的 |
|---|---|
| backend-java/src/main/java/cn/servicehub/auth/ | Cookie 会话、CSRF、成员角色与授权版本 |
| conversations/ConversationService.java、TopicService.java | 消息判重、抢单、话题切换、停止与短事务 |
| jobs/JobJournal.java、JobCoordinator.java | 业务任务与 Outbox、租约、重试预算、最终提交条件 |
| jobs/OutboxDispatcher.java、RabbitJobPublisher.java | RabbitMQ confirm、mandatory return 与至少一次投递 |
| jobs/JobConsumer.java、WorkerConfiguration.java | 手动确认、独立续租/回收循环及前后台消费 |
| knowledge/KnowledgeService.java、HybridRetriever.java | 文件版本、索引原子启用、全文与向量融合 |
| ai/SupportAgent.java、ToolRegistry.java | Spring AI 工具回环、参数约束、预算与显式确认 |
| realtime/LiveEventHub.java、LiveWebSocketHandler.java | 持久事件补放、身份复核、旧执行分片过滤 |
| operations/OperationsService.java、AuditService.java | 管理员恢复、审计与真实业务统计 |

上表除第一行外，Java 路径都相对 backend-java/src/main/java/cn/servicehub/。MyBatis 映射位于 backend-java/src/main/resources/mapper/，Flyway 迁移位于 db/migration/；不要只看 Controller 就解释并发行为。

## 组件如何协作

~~~mermaid
flowchart LR
    UI[React 三角色界面] --> WEB[Nginx]
    WEB --> API[Spring Boot API 多实例]
    API --> DB[(MySQL)]
    API --> REDIS[(Redis)]
    DB --> OB[Outbox 发布循环]
    OB --> MQ[RabbitMQ]
    MQ --> WORKER[独立 Java Worker]
    WORKER --> DB
    WORKER --> REDIS
    WORKER --> CHAT[qwen-plus]
    WORKER --> EMB[text-embedding-v4]
    REDIS --> API
~~~

Spring MVC 接收请求并进行字段校验；Spring Security 校验 Cookie、CSRF、Origin 与当前身份。角色校验之后还要检查对象归属，客户不能通过替换订单编号、会话编号或文档编号访问别人的数据。MyBatis-Plus 与显式 SQL 负责查询、行锁和条件更新，Flyway 管理可重复的结构迁移。

Redis 保存登录会话、限流和资源租约，并提供向量索引与实时通知。业务状态最终以 MySQL 为准。RabbitMQ 负责后台工作投递；Redis 通知丢失时，可由数据库事件和任务恢复流程补齐。

## 同一条消息为什么不会推进两次版本

~~~mermaid
sequenceDiagram
    participant C as 客户端
    participant A as API
    participant D as MySQL
    participant Q as Outbox与RabbitMQ
    C->>A: client_message_id 与消息内容
    A->>D: 鉴权并进入短事务
    A->>D: 查唯一键并校验原发送人和原内容
    alt 已存在且一致
        D-->>A: 原消息、run/job及accepted_epoch
    else 首次有效请求
        A->>D: 消息、epoch、run/job、事件、Outbox同事务写入
        D-->>A: 提交成功
        A-->>C: 原始任务标识与接收版本
        Q->>D: 领取待投递记录
    end
~~~

唯一约束是 tenant_id、conversation_id、client_message_id。相同键但内容不同返回 409。同一个逻辑消息的网络重试只返回原标识，不能再次推进 generation_epoch，也不能创建第二个逻辑任务。消息写入、版本推进或任务创建任何一步失败，都必须回滚整笔事务。

注意：幂等保护业务结果，不等于故障期间模型绝不会重复调用。公开介绍和面试中都不能承诺模型恰好执行一次。

## 话题与执行版本解决不同问题

| 标识 | 作用 |
|---|---|
| conversation_id | 整个客户业务会话 |
| topic_id | 同一会话内隔离的逻辑话题 |
| generation_epoch | 当前前台交互的单调版本 |
| run_id | 一次客服执行 |
| jobs.execution_version | 每次成功领取任务后递增的数据库 fencing token |
| budget_revision | 管理员显式重试后更新的预算版本 |

A→B→A 时，topic_id 可以再次回到 A，但 epoch 一定已变化。界面按会话、话题、run、epoch 与执行版本过滤流事件，后台最终提交也检查同一组身份。停止或话题切换只取代前台回答，知识导入等后台任务按固定文档版本提交。

被取消、未完成的助手回答和失效引用不会自动进入后续模型上下文。旧话题恢复只加载本话题的有效历史；跨话题订单必须由用户显式引用，并重新授权。

## 为什么既要 Outbox 又要数据库租约

业务事务先保存任务与 Outbox。发布者在事务外调用 RabbitMQ，收到 confirm 且没有被退回后，才用当前 Outbox 租约确认已投递。确认丢失时允许再次发布，消费者依据持久 job_id 幂等处理。

Worker 领取时递增 execution_version。旧 Worker 卡住后，替补可以获得更高版本；旧进程恢复后，其 owner/token 已不匹配。完成语句还要求任务处于 RUNNING、租约未到期、整轮截止时间未耗尽以及预算版本一致，影响行数必须为 1。领域写入、完成状态、事件和审计同事务提交。

Redis 的资源槽用于协调并发，不能代替数据库围栏。续租成功也不代表权限或话题仍然有效，提交前仍须复核。暂停整个进程时，RabbitMQ 还需要通过心跳识别失联连接；故障检测耗时与数据库接管耗时分别记录。

## 模型和工具为什么不在事务里运行

网络调用可能持续数秒甚至超时，不能占着授权行、会话行或工单行的锁等待供应商。检索、模型调用和队列网络操作在事务外执行，最终提交时用短事务再次锁定当前授权与业务对象。

撤销权限与业务写入争用同一授权行，因此提交按数据库锁顺序明确先后。若业务写入已提交再撤权，已提交结果仍然存在；若撤权先提交，后续提交必须失败。外部不可逆支付和退款不在本项目工具范围内，取消本地任务也不能回滚已完成的外部副作用。

## 资源与预算

项目执行并发总数为 4，BACKGROUND 最多占 3；模型准入也单独采用总 4、后台最多 3。前后台分别消费队列，prefetch=1；投递、续租、回收和清理各用独立调度，避免所有工作线程等待慢模型时无人续租。

前台排队限时 60 秒，整轮执行从首次领取起 120 秒，恢复重试不重置该截止时间。单次模型调用最多 45 秒且受剩余预算限制；前台最多 6 次模型请求、4 次工具执行。自动执行最多 3 次总尝试，前两次失败后分别退避 5 秒、30 秒。权限拒绝、取消和永久性错误不自动重试。

这些是可配置的运行边界，不代表四路并发就构成某种生产容量保证。普通 HTTP 吞吐和真实模型时延分开测量。

## 检索与模型的边界

文档先保存私有原文件，按版本解析并分块，再调用真实 Embedding。向量写入临时执行代，成功后才原子切换有效版本。MySQL ngram 全文检索与 Redis HNSW 向量检索各召回候选，再用 RRF 融合；召回前约束租户和有效代，提交回答前重新验证来源。

ServiceHub 使用 Spring AI 与官方兼容 SDK 接入百炼，并自行编排受控工具回环。它没有使用 LangChain 或 LangGraph。独立问数 Agent 是后续单独的 Python/LangGraph 项目，不能把这两个项目的依赖混写。

供应商 usage 缺失时保留未知，真实 0 与未知分开。工具参数不能携带模型指定的用户或租户身份；身份来自服务端当前会话。

## 用哪些证据回答面试问题

查看 docs/verification/java-contract-matrix.md、java-concurrency-acceptance.md 和 java-acceptance.md。测试覆盖真实双 API 进程、Worker 进程与 MySQL/Redis/RabbitMQ；用受控网络服务做离线故障注入，真实百炼验收单独显式运行。

小规模本机测量不能外推为线上 QPS、SLA 或普遍准确率。研究项目、独立 Agent 与 ServiceHub 的结果也应分别介绍。
