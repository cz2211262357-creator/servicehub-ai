# ServiceHub Java 启动与故障恢复

Java 版使用独立的 Compose 项目、数据库、Cookie、上传目录与端口。Python 版保留，不能直接覆盖旧数据库，也不将旧 Redis 会话和旧后台任务搬到 Java。首次操作先确认源码目录 W、项目根目录 R 与当前分支，所有直接管理的工具、缓存、数据和输出都留在 R 内。

## Windows 开发启动

在 PowerShell 中将 R 改为实际项目根目录，W 改为当前已验收的 Java 源码目录。普通克隆时两者相同，隔离工作树位于 R 内时两者可以不同。

~~~powershell
$R = 'D:\work\servicehub-ai'
$W = $R
Set-Location -LiteralPath $W
.\scripts\bootstrap-java.ps1 -WorkspaceRoot $R
. .\scripts\java-env.ps1 -WorkspaceRoot $R
~~~

bootstrap-java.ps1 按锁文件安装项目 JDK21 和 Maven，并仅在不存在时生成 R/.env.java。它不会覆盖已有凭证；已有 Java 数据但凭证丢失时，应从私有备份恢复，不能重新生成后假装数据仍可直接使用。

R/.env 中的 SERVICEHUB_MODEL_* 与 SERVICEHUB_EMBEDDING_* 会由 java-env.ps1 按白名单读取，也可放在忽略的 R/.env.java 中。配置 qwen-plus、text-embedding-v4、1024维及与 Key 地域一致的兼容接口。不要把真实值粘贴到命令行参数、截图、日志、README 或 Git 提交中。

~~~powershell
docker compose --env-file "$R/.env.java" -f "$W/compose.java.yaml" --profile api --profile worker --profile web build
docker compose --env-file "$R/.env.java" -f "$W/compose.java.yaml" up -d --wait mysql redis rabbitmq
docker compose --env-file "$R/.env.java" -f "$W/compose.java.yaml" --profile api --profile worker --profile web up -d api worker web
Invoke-RestMethod http://127.0.0.1:8001/api/v1/health/ready
~~~

API 首次启动自动执行 Flyway。源码构建使用 Maven Wrapper；本机直接运行可在 backend-java 的 Maven 项目中使用 api 或 worker profile。Docker 运行镜像使用 UID/GID10001，Linux bind mount 上传目录要事先为该账号设置权限。

| 服务 | 本机地址 |
|---|---|
| Web | http://localhost:8081 |
| API | http://127.0.0.1:8001 |
| MySQL | 127.0.0.1:3307 |
| Redis | 127.0.0.1:6381 |
| RabbitMQ | 127.0.0.1:5673 |
| RabbitMQ 管理页 | http://127.0.0.1:15673 |

全部发布端口显式绑定 127.0.0.1。8000/8080 等旧 Python 端口和旧数据保持独立。

## 首次管理员与账号恢复

若 R/.venv 尚不存在，先用已安装的 uv 创建项目环境；所有新环境与缓存显式指向 R：

~~~powershell
$env:UV_CACHE_DIR = "$R/.cache/uv"
$env:UV_PYTHON_INSTALL_DIR = "$R/.python"
$env:UV_PROJECT_ENVIRONMENT = "$R/.venv"
$env:UV_PYTHON_NO_REGISTRY = "1"
uv sync --project $W --all-groups --frozen
~~~

维护工具使用现有项目 Python 环境，依赖由 uv 按 uv.lock 安装。隔离工作树复用 R/.venv，普通克隆使用当前根目录的 .venv；不要为了工具安装修改系统 Python。

首次启动没有固定公开密码。待空库迁移完成后，在停止 API/Worker 的维护窗口运行账号初始化工具，密码由终端隐藏输入：

~~~powershell
& "$R/.venv/Scripts/python.exe" -B "$W/scripts/java-account-admin.py" bootstrap --target-env "$R/.env.java" --tenant-name "测试企业" --email "admin@example.com" --display-name "管理员"
~~~

初始化只允许空目标，重复执行不能覆盖已有账号。登录管理员后在成员页面创建客服和客户；没有对接外部电商平台时，真实业务订单需由维护数据流程接入，验收测试使用独立合成订单。

迁移过来的用户保留业务归属，但默认禁用且不导入旧密码。维护人员可以逐个重置新密码并启用：

~~~powershell
& "$R/.venv/Scripts/python.exe" -B "$W/scripts/java-account-admin.py" reset-password --target-env "$R/.env.java" --tenant-id 需要恢复的租户编号 --user-id 需要恢复的用户编号
~~~

该操作推进授权版本并写审计，不能用旧 Session 继续登录。非交互 CI 可使用明确的 --password-stdin，将密码通过标准输入传递；不能放进命令行参数。

## PyCharm 与构建路径

打开 W 为项目，添加 backend-java/pom.xml 为 Maven 工程，JDK 选择 R/.tools 下锁定的 JDK21。启动终端先点入 java-env.ps1，分别检查：

- JAVA_HOME 位于 R/.tools 内；
- MAVEN_USER_HOME 位于 R/.cache/maven；
- Maven 本地依赖库为 R/.cache/m2；
- Java 临时文件位于 R/.cache/java-tmp；
- backend-java/.mvn/settings.xml 和 Wrapper 来自当前 W。

不要让 IDE 自行切换到用户级 Maven 仓库后仍声称已验证项目路径。测试中的 BuildBoundaryIT 会在 R、W、backend-java 三个启动目录实际核查分发缓存与依赖库。

Docker 引擎的全局镜像、BuildKit 缓存、容器层和运行时存储由 Docker Desktop 管理，不受 Compose 源码路径完全约束。项目会排除 .env*、.cache、.tools、.data、.superpowers、target 和 node_modules 等私有构建上下文；不修改用户全局 Docker 存储配置，也不运行全局 prune。

## 从 Python 移交业务数据

先停止两边的 API 与 Worker 写入，备份源库、原始文件及私有凭证。export 只读源库，初版不做双写、在线增量合并或原地升级。

~~~powershell
$bundle = "$R/.data/java/transfer/migration-001/manifest.json"
& "$R/.venv/Scripts/python.exe" -B "$W/scripts/java-data-transfer.py" export --source-env "$R/.env" --output $bundle
& "$R/.venv/Scripts/python.exe" -B "$W/scripts/java-data-transfer.py" validate --input $bundle
& "$R/.venv/Scripts/python.exe" -B "$W/scripts/java-data-transfer.py" import --target-env "$R/.env.java" --input $bundle --allow-empty-target
~~~

目标必须是已经执行 Java Flyway 的空业务库。源、目标物理数据库指纹相同，目标非空，重复 ID，租户关系损坏，金额精度不合规，时间没有 UTC，未知字段或文件越界/哈希不符时，工具应拒绝并以非零退出；输出不含凭证。数据库导入失败要整笔回滚，不能留下半套业务记录。

历史消息映射到默认话题供查看，旧 run/job、Outbox、工具调用、资源租约与登录凭证不恢复。旧未确认草稿失效，需要客户重新确认新草稿；已存在正式工单继续保留。

知识元数据及原文件保留，但不会把旧向量或旧有效索引标记当作 Java 已就绪。迁移版本显示 migration_reindex_required；重设有权限的管理员账号后，为对应文档上传新版本，由 Java 真实解析和重新向量化，索引完成后才对客户启用。

数据库事务不能原子回滚文件系统。文件先暂存并校验、再在事务中提升，确认事务回滚时只清理本批次拥有的文件；COMMIT 应答丢失时记录 commit_outcome_unknown 并保留文件，不能以另一个连接暂时查不到记录作为删除依据。普通上传同样只在明确回滚时删除新文件；异常退出残留应依据移交日志、文件 SHA 和数据库引用核对，不能盲删整个 uploads。移交包包含业务资料，始终留在忽略的 R/.data/java/transfer 中。

## 备份与恢复

需要同时备份 MySQL、原始上传文件和私有配置，只有向量索引备份不能恢复业务。文件名固定到不可变文档版本，因此在暂停业务写入后，可以取得数据库与上传目录的一致备份。

冷备份可停掉本项目的 API/Worker/Web 以及 MySQL/Redis/RabbitMQ，确认停止后，将 R/.data/java 复制或归档到 R 内另一个私有备份目录，并单独保管 R/.env.java。不要把备份放进源目录自身，也不要覆盖唯一的一份旧备份。数据库运行中不能直接复制 MySQL 数据目录当作一致备份。

恢复先使用新的空恢复目录和原版本镜像，核对备份校验值、文件清单、MySQL 版本及凭证，再还原。保留故障现场与旧数据目录，核对租户数量、订单金额、工单状态、文档 SHA 和登录后权限，之后才切换入口。恢复后的旧 Cookie 不应继续复用；队列按当前 MySQL 任务状态恢复。

## 故障排查顺序

| 现象 | 先核对 | 恢复动作 |
|---|---|---|
| RabbitMQ 不可用 | ready、Outbox待投递与任务状态 | 恢复代理后观察补投，不手工复制逻辑任务 |
| Worker 退出 | DB租约、deadline、attempts、execution_version | 启动替补，等待自然回收和投递；旧实例无权提交 |
| Redis 短断 | Cookie鉴权、资源槽、事件数据库游标 | 恢复 Redis；持久取消和业务状态仍以 MySQL 为准 |
| 模型 429/5xx | 实际错误码、请求预算与剩余截止时间 | 自动退避受总3次限制，不返回虚假成功 |
| 模型永久错误或权限撤销 | error_code、auth_version | 修复配置或重新授权；取消任务不能自动复活 |
| 文档检索不命中 | 当前租户、有效版本、索引维度与当前授权 | 修复后重新导入新版本，禁止绕过授权静默检索 |
| WebSocket 断线 | Origin、Cookie、after_seq及执行版本 | 按数据库游标补放，不能直接拼回旧任务分片 |

管理员重试只适用于当前仍有效且 FAILED 的任务。它沿用逻辑标识但更新预算版本，保留 lifetime_attempts；CANCELLED、过期话题、撤权主体不能通过重试恢复。

## 验证命令

~~~powershell
. "$W/scripts/java-env.ps1" -WorkspaceRoot $R
& "$W/backend-java/mvnw.cmd" -f "$W/backend-java/pom.xml" verify
& "$W/scripts/java-system-tests.ps1" -WorkspaceRoot $R -ReportPath "$R/.cache/java-verification/system-delivery.json"
& "$W/scripts/java-online-check.ps1" -WorkspaceRoot $R -ReportPath "$R/.cache/java-verification/online-delivery.json"
& "$W/scripts/java-benchmark.ps1" -WorkspaceRoot $R -ReportPath "$R/.cache/java-verification/benchmark-delivery.json"
~~~

普通回归使用真实临时 MySQL/Redis/RabbitMQ 和受控网络服务，不请求真实百炼。在线验收为显式独立入口，会使用当前配置发出少量真实模型请求。私有报告位于 R/.cache/java-verification；公开记录只保留脱敏统计和可核查版本。

上述启动、初始化、导入与回退步骤仍须由交付验收实际执行后，才可在 java-delivery-evidence.md 标记通过。

上传位置默认是 R/.data/java/uploads。可在私有 R/.env.java 设置绝对路径 SERVICEHUB_JAVA_UPLOAD_DIR，限定在 R/.data/java 内且不能经过符号链接。维护工具和本机 Java 读取同一设置；Compose 将该宿主目录挂到容器内 /workspace/.data/java/uploads。更改位置前应停止 API/Worker 并移动已核对的文件，不能只修改环境变量后继续使用旧库。

Redis 使用固定镜像的官方入口脚本加载 Search 等模块。带密码的 shell 包装仍需 exec /usr/local/bin/docker-entrypoint.sh redis-server，不能直接 exec redis-server，否则 PING 正常也可能缺少 FT.SEARCH/HNSW。ComposeRedisIT 按实际 Compose command 启动独立容器并调用 Search 命令。
