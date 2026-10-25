# ServiceHub AI

[![Java platform verification](https://github.com/cz2211262357-creator/servicehub-ai/actions/workflows/java-ci.yml/badge.svg)](https://github.com/cz2211262357-creator/servicehub-ai/actions/workflows/java-ci.yml)

**基于 Java / Spring Boot 与 React 的电商售后客服与知识问答平台。** 接入百炼真实对话与 Embedding，将知识检索、订单核查、售后草稿、客户确认、人工接待和运营审计连接为完整业务流程。

项目采用模块化单体 API 加独立 Worker。重点实现多租户授权、消息幂等、事务 Outbox、RabbitMQ 投递、数据库租约围栏、话题切换和旧回答拦截。订单使用可替换的合成数据，模型请求、向量索引、队列和数据库写入均使用真实链路；没有接入外部电商、物流或支付退款系统。

原 FastAPI 版本继续保留，历史启动与实测说明见 [Python 版本说明](docs/operations/Python版本使用与验收.md)。当前主线交付以 Java 后端为准。

## 业务能力

| 角色 | 功能 |
|---|---|
| 客户 | 会话与知识问答、引用来源、本人订单、售后草稿确认、工单状态、停止回答、话题切换与转人工 |
| 客服 | 服务队列、竞争接单、客户上下文、人工回复与工单审核 |
| 管理员 | 私有知识文件和版本、成员授权、任务恢复、审计、依赖健康与真实运营统计 |

## 技术栈

| 部分 | 实现 |
|---|---|
| 前端 | React、TypeScript、Vite、TanStack Query、WebSocket |
| Java 运行时与 API | JDK21、Spring Boot4.1.1、Spring MVC、Spring Security |
| 数据访问 | MyBatis-Plus3.5.17、显式 SQL、Flyway、MySQL8.4 |
| 检索与缓存 | Redis8.4 Search/HNSW、MySQL ngram 全文、RRF、Lua 限流和资源准入 |
| 消息与执行 | RabbitMQ4.3.6、Spring AMQP、事务 Outbox、独立 Worker、数据库租约与执行版本 |
| 模型与工具 | Spring AI2.0.1、兼容 SDK、qwen-plus、text-embedding-v4/1024维、受控 Tool Calling |
| 验证与交付 | Maven Wrapper、JUnit、Testcontainers、Vitest、Docker Compose、GitHub Actions |

ServiceHub 的模型回环由 Java 实现，未使用 LangChain 或 LangGraph。它包含受业务边界约束的工具执行，不能将尚未交付的独立 Python/LangGraph 问数项目算作本项目能力。

## 架构

~~~mermaid
flowchart LR
    UI[React / TypeScript] --> N[Nginx]
    N --> API[Spring Boot API 多实例]
    API --> DB[(MySQL 业务权威源)]
    API --> R[(Redis)]
    OB[独立 Outbox 发布循环] --> DB
    OB --> Q[RabbitMQ]
    Q --> W[Java Worker]
    W --> DB
    W --> R
    W --> L[qwen-plus]
    W --> E[text-embedding-v4]
    R --> API
~~~

源码在 backend-java/，前端继续使用 frontend/；历史 Python 代码位于 backend/。业务模块包含 auth、conversations、orders、aftersales、knowledge、jobs、ai、realtime、operations，分别管理权限、领域状态与运行边界。

## 核心实现

**权限与最终提交。** 使用独立的 HttpOnly Cookie 会话、CSRF 和 Origin 校验；用户身份由服务端注入，查询同时约束租户及对象归属。工具调用、检索输出及业务提交重新检查当前权限。撤权与业务提交通过同一授权行的锁顺序明确先后，恢复任务也不复用旧的授权结论。

**重复请求与话题切换。** 相同 client_message_id 返回原消息、run/job 和接收版本；内容冲突返回409。首次消息与会话版本、任务、事件、Outbox 在同一事务中提交。前台 epoch 单调递增，A→B→A 也不能接纳旧 A 分片；停止、人工接管、话题切换后的迟到回答均需通过服务器提交校验。

**消息与旧写保护。** Outbox 发布在收到 RabbitMQ confirm 且未被退回后才确认。消费采用至少一次语义，业务结果依赖幂等和数据库条件提交。每次领取任务推进 execution_version；过期 Worker 即使恢复，也不能覆盖替补结果。模型请求可能在故障中重复，不承诺恰好调用一次。

**前后台资源隔离。** 执行和模型准入分别限制总并发4、后台最多3，为交互保留名额。前后台分别消费且 prefetch=1，投递、续租、恢复和清理使用独立循环。前台排队最多60秒、执行硬截止120秒，恢复不会重置截止时间；自动执行最多3次，前两次失败分别退避5/30秒。

**混合检索与来源。** 私有 TXT、Markdown 和文本 PDF 保留原文件、版本及页码。全文与向量双路召回后使用 RRF 融合；召回前过滤租户和有效索引代，回答提交前复核引用。新索引成功后才原子切换有效版本，旧来源不能绕过当前文档授权。

**受控模型工具。** 仅开放 search_policy、lookup_order、prepare_after_sale；参数、输入输出、调用次数、截止时间和权限均由服务端校验。模型可以准备售后草稿，正式工单需要客户确认。Token usage 缺失时保留未知，与真实0区分。

**实时与恢复。** 数据库事件序号用于补放，Redis 只负责通知和临时流事件。WebSocket 会定期重新授权，客户端与服务端同时过滤过期执行，慢连接有有界缓冲和发送超时。

## 启动

需要 Git、可用 Docker 环境、uv，以及具有对应模型权限的百炼 Key。首次克隆：

~~~powershell
git clone https://github.com/cz2211262357-creator/servicehub-ai.git
Set-Location servicehub-ai
$R = (Get-Location).Path
$W = $R
.\scripts\bootstrap-java.ps1 -WorkspaceRoot $R
. .\scripts\java-env.ps1 -WorkspaceRoot $R
~~~

在忽略的本地配置中填写真实模型接口与 Key，再重新加载 java-env.ps1。初始化工具不会覆盖已有凭证。构建及启动：

~~~powershell
docker compose --env-file "$R/.env.java" -f "$W/compose.java.yaml" --profile api --profile worker --profile web build
docker compose --env-file "$R/.env.java" -f "$W/compose.java.yaml" up -d --wait mysql redis rabbitmq
docker compose --env-file "$R/.env.java" -f "$W/compose.java.yaml" --profile api --profile worker --profile web up -d api worker web
~~~

Web 为 http://localhost:8081，API 为 http://127.0.0.1:8001。所有 Compose 端口只绑定127.0.0.1。API 自动执行 Flyway；首次管理员需通过维护工具设置自己的密码，见 [启动与故障恢复](docs/operations/Java启动与故障恢复.md)。

所有直接管理的工具、缓存、构建输出与持久数据位于 R 内，私有数据不参与 Git 或 Docker 构建上下文。Docker Desktop 引擎自身的镜像、容器层和全局缓存单独说明，不宣称被项目目录完全隔离。

## 验证与测量

2026-10-25，[公开代码7b0c470的完整CI](https://github.com/cz2211262357-creator/servicehub-ai/actions/runs/40194564025)通过355项Java测试（35单元、320集成，失败/错误/跳过0）、38项前端测试、类型检查、生产构建及两个容器镜像构建。

最终计数和版本以 [Java 验收记录](docs/verification/java-acceptance.md) 及 [交付证据](docs/verification/java-delivery-evidence.md) 为准。普通回归使用真实临时 MySQL/Redis/RabbitMQ，供应商异常通过受控网络服务注入；真实百炼验收为显式独立入口。

- 原39个HTTP接口与旧 Python 运行时逐项对照，另验证3个话题/停止扩展接口及 WebSocket；核对角色、Cookie、CSRF、权限、错误、时间与金额格式。
- 实际双 API 进程覆盖重复消息与事务回滚、双页面 CAS、A→B→A、重复售后确认和20名不同客服抢单。
- 实际 Worker 进程覆盖取消后重启、暂停后租约替换、RabbitMQ confirm 丢失、数据库短断、有限重试和前后台资源隔离。
- 2026-10-25 真实百炼链路已验证 PDF 向量化、中文带引用回答、订单工具、售后草稿、客户确认及人工回复；15个开发路由样例中，仅3个模糊样例调用分类模型一次。
- 本机双 API、小数据集的普通读接口，在并发1/10/20下各100次请求，错误数均0，P95为18.63/35.03/25.05ms。它不表示生产容量，也不包括大模型时延。

~~~powershell
. .\scripts\java-env.ps1 -WorkspaceRoot $R
& .\backend-java\mvnw.cmd -f backend-java/pom.xml verify
& .\scripts\java-system-tests.ps1 -WorkspaceRoot $R -ReportPath "$R/.cache/java-verification/system-delivery.json"
& .\scripts\java-online-check.ps1 -WorkspaceRoot $R -ReportPath "$R/.cache/java-verification/online-delivery.json"
& .\scripts\java-benchmark.ps1 -WorkspaceRoot $R -ReportPath "$R/.cache/java-verification/benchmark-delivery.json"
~~~

前端使用 npm --prefix frontend ci，再运行 typecheck、test -- --run 和 build。CI 同时核对生成接口类型漂移。真实模型验收需要本地私有配置，不将真实 Key 放进公开 CI 或构建参数。

## 阅读与边界

- [关键源码与面试讲解](docs/operations/Java关键源码与面试讲解.md)
- [接口契约矩阵](docs/verification/java-contract-matrix.md)
- [并发与故障验收矩阵](docs/verification/java-concurrency-acceptance.md)
- [话题与执行一致性规范](docs/superpowers/specs/2026-10-22-execution-consistency-design.md)
- [真实模型与验收记录](docs/verification/java-acceptance.md)

本项目不包含支付退款写入、OCR、微服务拆分或跨地域容灾。公网部署还应结合目标环境配置 HTTPS、独立凭证、备份及容量验证。
