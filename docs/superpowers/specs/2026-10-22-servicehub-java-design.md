# ServiceHub AI Java 后端迁移设计

日期：2026-10-22
状态：用户已批准设计并进入实施计划；尚未开始迁移代码或安装新依赖。

## 目标与已知约束

将 ServiceHub AI 的业务 API、权限、数据访问、知识检索、模型接入与后台执行迁移到 Java。复用现有 React / TypeScript 三角色界面，用完整业务流程展示 Java 全栈与后端工程能力。另建 Python Agent 项目承担 Agent 编排与问数能力，两者各自可运行、可验收。

用户已有 Java 基础，尚无 Spring Boot 项目经历。交付需包括模块讲解、启动步骤与取舍说明，便于理解源码及准备面试。项目直接管理的代码、工具、构建缓存、测试输出与持久数据仍放在D:/研究生/简历1/qz-project内；Docker引擎全局存储单独说明，不因迁移修改用户全局Docker设置。

当前开发分支 feat/servicehub-platform，核对时工作区干净，HEAD 为 162f086。公开仓库中的 Python 版本继续保留，作为行为回归基线。当前版本共有 37 个路由模块中的 HTTP 接口、2 个健康接口和 1 个 WebSocket；迁移范围保留这 39 个 HTTP 接口及 1 个 WebSocket；根据新增的打断与话题要求再增加 3 个 HTTP 接口，总计 42 个，不含框架自动生成的文档页面。

## 方案比较与选择

| 方案 | 收益 | 成本与边界 |
|---|---|---|
| A：Java 完整后端，复用 React，另建 Python Agent（推荐） | Java 项目职责完整；两个项目各自突出工程与 Agent 能力 | 需要迁移权限、RAG、任务和故障恢复，工作量最大 |
| B：Java 业务后端，Python AI 子服务 | 能复用较多现有 AI 代码 | ServiceHub 运行依赖两种后端，接口、故障和部署边界更多 |
| C：保留现有客服，新增另一个 Java 业务系统 | 两套系统互不影响 | 需要重做一套业务与页面，简历项目容易重合 |

本设计按 A 编写。第一期采用模块化单体 API 加独立 Worker，便于理解事务边界与定位故障。

## 技术选择

| 部分 | 选择 | 实际职责 |
|---|---|---|
| 前端 | 现有 React、TypeScript、Vite、TanStack Query | 客户门户、客服工作台、管理员控制台 |
| Java 运行时 | 项目内 JDK 21 LTS | 保持项目运行环境可复现；现有全局 JDK 23 保持原配置 |
| 后端 | Spring Boot 4.1.x、Spring MVC | HTTP API、校验、统一错误和配置 |
| 权限 | Spring Security | HttpOnly Cookie 登录会话、CSRF、角色和租户校验 |
| 数据访问 | MyBatis-Plus、明确 SQL、Flyway | 表结构版本、查询、行锁与条件更新 |
| 数据 | MySQL 8.4、Redis 8.4 | 业务权威源；会话、Lua 限流、并发槽、向量检索、通知 |
| 后台消息 | RabbitMQ、Spring AMQP | 可靠投递、确认、重投、死信及任务消费 |
| 模型 | Spring AI 2.0.x、百炼兼容 API | 真实对话、受控工具调用与 Embedding |
| 验证与运维 | JUnit、Testcontainers、Actuator、Docker Compose、GitHub Actions | 真实依赖、集成验证、健康状态与自动构建 |

Spring AI 官方兼容矩阵覆盖 Spring Boot 4.0/4.1；MyBatis-Plus 官方已有 Boot 4 starter。实现计划将锁定稳定补丁版本、Maven Wrapper 和容器镜像，不使用预发布依赖。设计核查时官方文档展示的版本包括 Boot 4.1.1、Spring AI 2.0.1、MyBatis-Plus 3.5.17；实际可构建性仍须通过依赖解析和测试确认。

模型沿用当前已接入的 qwen-plus、text-embedding-v4 / 1024 维。密钥仅从忽略的本地环境配置读取，任何文档、日志和公开仓库都不记录真实值。

## 目录与运行隔离

- Java 代码放在 backend-java/，采用按业务能力划分的包；前端继续使用 frontend/。
- 新增 compose.java.yaml，与当前 Python Compose 使用不同项目名、数据卷和端口。
- Java 试运行端口：Web 8081、API 8001、MySQL 3307、Redis 6381、RabbitMQ 5673、管理界面 15673，全部仅绑定 127.0.0.1；启动前检查占用。
- Java 数据、上传文件放入 .data/java/；工具放入 .tools/；Maven 缓存放入 .cache/m2/。
- 实施时在 .cache/worktrees/ 下建立隔离 Git 工作树，沿用当前会话逐项实现的方式。
- Java 版使用独立数据库与种子数据。第一期提供业务数据导出/导入与核对步骤，不对当前运行库做原位覆盖或双写切换。
- Python 版保持可运行；Java 验收通过后再更新主启动入口、GitHub 说明与简历。

## 业务模块与接口合约

| 模块 | HTTP 接口数量 | 迁移重点 |
|---|---:|---|
| 登录与成员 | 6 | 登录、当前身份、登出、成员管理和会话失效 |
| 会话 | 10 | 消息、上下文、转人工、抢单、关闭和事件回放 |
| 订单 | 2 | 当前客户订单与详情，避免跨用户读取 |
| 售后 | 6 | 草稿、明确确认、幂等工单与审核状态 |
| 知识 | 7 | 上传、版本、有效文档、鉴权下载和停用 |
| 运维与指标 | 6 | 任务、重试、审计、运行状态和指标 |
| 健康 | 2 | 存活与就绪 |

另迁移 /api/v1/conversations/{conversation_id}/live WebSocket。

从现有 OpenAPI 和前端调用冻结契约，逐项核对方法、路径、请求字段、响应 JSON、分页、状态码、金额与时间表示。保留 snake_case 字段、现有角色值和 error.code/message/request_id 错误结构；不添加导致前端整体改写的通用响应套壳。Spring 默认错误码不能直接替代现有 401/403/404/409/422/429 行为。

现有登录是 Redis 随机会话与 HttpOnly Cookie，并非 JWT。Java 版按此语义实现 Spring Security 鉴权，写请求校验 CSRF，WebSocket 校验 Origin 并定期复核身份。会话键、索引和 Cookie 使用 Java 版独立命名，避免同时运行时互相覆盖。租户身份始终由服务端会话推导。

## 并发、一致性与消息

1. 业务写入与 Outbox 记录在同一 MySQL 事务提交；事务外调用模型与消息代理。
2. 消息重复提交使用唯一业务键和内容摘要，内容冲突返回 409；重复售后确认只产生一张工单。
3. 同一会话抢单通过状态、负责人及版本条件更新，仅一名客服成功。消息判重、epoch、run/job/outbox/event同事务，重复返回原消息及原触发任务，不能再推进epoch。
4. 人工接管推进会话版本；AI最终提交同一短事务锁当前授权行与领域对象，校验auth_version、状态、topic/epoch、deadline及DB owner/租约/token，CAS失败整笔回滚。后台导入按文档版本提交，不依赖聊天epoch。
5. Outbox 发布收到 RabbitMQ confirm 后才标记已投递；确认丢失可能再次发布，消费端按 job_id 幂等。
6. Worker 手动确认消息；失败重试、最终失败和管理员重试均持久化。需重试的任务先保存状态和下一次投递记录，再确认旧消息。
7. 项目全局执行并发默认4、供应商有效准入4，BACKGROUND两层均最多3并保留1个交互名额；投递、租约续期与回收使用独立调度，避免慢模型占满任务线程后无法恢复。
8. 旧执行与新执行通过数据库执行版本隔离；进程或 RabbitMQ 故障后能够恢复队列任务。
9. RabbitMQ 投递语义为至少一次。业务最终结果依靠幂等和提交校验保护；模型调用本身可能因故障而重复，不能承诺恰好调用一次。
10. 会话事件继续以 MySQL 单调序号为回放依据；Redis 用于通知，断线或通知丢失后可按游标补放。

RabbitMQ 的明确职责是知识导入和 AI 长任务队列。Redis 继续承担会话、限流、检索及实时通知。RabbitMQ 暂时不可用时，已提交任务仍保留在 Outbox 中，运行状态显示排队与依赖异常。

## RAG 与模型接入

保留 TXT、Markdown、文本 PDF 的私有上传、分块、版本和页码。Java 端使用文本解析库和 Spring AI 对接真实 Embedding；向量维度不匹配时明确失败。

保留全文加向量双路召回及 RRF：先约束当前租户和有效文档版本，再融合候选，返回前和回答提交前复核权限。历史引用固定到原始版本；下载需验证本人会话引用与访问权限。

仅开放 search_policy、lookup_order、prepare_after_sale，服务端注入身份并再次鉴权。正式售后创建仍由客户页面明确确认。模型返回的工具名、参数、次数、耗时与输出长度受限；供应商 usage 未返回时保留为空。

## 验收标准

- 39 个原 HTTP 接口、3 个新增话题/停止接口及 WebSocket 有合约核对记录，React 三角色完整业务流程通过。
- 两个 Java API 实例下，20 次重复消息只保存一条消息及一个逻辑任务；20 次重复确认只生成一张工单；20 名不同客服抢单仅一人成功。
- 人工接管、文档停用、权限变更、任务执行权失效后，迟到结果不能越权提交。
- 在真实 MySQL、Redis、RabbitMQ 上验证投递确认丢失、Worker 强制退出、代理重启、Redis 短暂中断、慢任务及最终失败。
- 百炼真实对话、Embedding、引用问答、订单工具、草稿确认与人工回复形成可重复验收流程。
- Java 测试、前端测试、类型检查与构建进入 CI；并发与模型性能分别测量，记录环境、样本量、错误率和延迟。
- Java 版使用自己的测试结果和证据，旧版 94/18 测试数量不作为新版完成证明。
- 交付架构图、启动步骤、关键源码讲解、并发时序、故障恢复步骤和可核实的简历条目。

## 范围边界

第一期覆盖现有业务与上述 RabbitMQ 改造，不新增支付退款、外部电商/物流对接、OCR、跨地域容灾或微服务拆分。生产部署仍需独立凭证、HTTPS、备份及目标容量验证。完成实际代码与测试前，简历和 GitHub 主页面继续描述当前已实现技术。

## 多轮对话补充要求

用户新增的对话打断与话题切换要求为必选范围，见 [共同补充设计](2026-10-22-conversation-interruption-design.md)。必须实现前台取消、后台独立归属、迟到结果拦截、上下文白名单和安全恢复；见[执行一致性规范](2026-10-22-execution-consistency-design.md)的状态表、事务与资源规则，以及[验收矩阵](../../verification/java-concurrency-acceptance.md)。售后草稿与 Agent 执行确认分别管理；已提交业务结果不因打断被撤销。

## 官方参考

- https://docs.spring.io/spring-boot/system-requirements.html
- https://github.com/spring-projects/spring-boot/wiki/Supported-Versions
- https://docs.spring.io/spring-ai/reference/getting-started.html
- https://baomidou.com/en/getting-started/install/

## 当前进度

- [x] 核对现有源码、接口、部署与运行环境。
- [x] 写出迁移方案和验收标准并自查。
- [x] 用户审阅书面设计，按推荐方案推进。
- [x] 编写实施计划；用户逐项审查后已补充一致性、资源与验收规范。
- [x] 完成项目环境、Java实现、真实部署/模型验收、完整公开CI、GitHub发布及保留证件照的简历更新；证据见../../verification/java-delivery-evidence.md。
