# 逐项审查处理记录

日期：2026-10-22。范围：设计与实施计划修订，未声称 Java 已实现或测试已通过。

## 八项审查的处理结果

以下勾选表示规范已经补齐并同步到实施计划；代码、真实依赖及在线验收仍待执行。

| 检查项 | 规范决策 | 实施/验收位置 |
|---|---|---|
| [x] 消息幂等与epoch原子性 | tenant+conversation+client_message_id唯一；判重/epoch/run/job/outbox/event同事务；返回原消息和不可变触发任务，冲突409 | 一致性规范§2；计划2/4/7；A01/A02/A03 |
| [x] topic/run/lease状态表 | 当前话题指针为权威；逻辑run和领取token分开；自动/人工重试、等待输入、取消、过期状态有明确转换和HTTP映射 | 规范§1/3；计划6/7/10/12 |
| [x] RabbitMQ Outbox与防旧写 | outbox发布权也有版本CAS；confirm后标记；consumer按job幂等；DB owner/token/state/expiry条件更新和正式结果同事务 | 规范§4；计划6/10；A07/A08/A09/A10 |
| [x] 前台与长任务资源隔离 | Java执行/模型总4，后台两层最多3；独立消费与调度；前台60秒排队、120秒硬执行deadline，自动恢复不重置 | 规范§6；计划6/8/10；A12/A13/A20 |
| [x] 动态权限与不可逆操作 | auth_version+同事务授权锁；撤销与业务提交线性化；写草稿/知识激活/报告也防TOCTOU；不新增外部不可逆写工具 | 规范§5；计划3/5/8/9/10；A05/A06/A16及P02/P03 |
| [x] Docker缓存与Maven路径 | 区分项目直接管理路径和Docker引擎全局存储；显式MAVEN_USER_HOME与maven.repo.local；worktree核验、127.0.0.1、Git/build context排除 | 规范§7；计划1/13；A18 |
| [x] 原39+3 HTTP与WebSocket契约 | 已实际导出原39清单/OpenAPI摘要；逐项验证身份、CSRF、错误、字段、金额、时间、上传、WS；新增3/W01单列 | 接口契约矩阵H01–H39/N01–N03/W01；计划2/13；A19 |
| [x] 故障/并发通过标准 | barrier/latch注入窗口，竞态每项20轮且0违反；规定DB不变量、时限与完整机器报告，跳过/未命中故障不算通过 | 验收矩阵A01–A20；独立Agent保留P01–P03 |

## 本次核对到的现有实现

- Python ConversationService.send_message 已在会话行锁内判重、推进epoch、写message/run/job/outbox；重复不会再推进epoch。Java必须完整复现这些事务语义，而非新增一个内存if。
- Python JobService.lock_valid 已校验owner/execution_version/lease_expires_at；Java继续使用execution_version作为fencing token，不加重复的lease_token字段。补充了DB提交CAS、状态表和发布者lease_version说明。
- 原JobService.acquire最多4次自动尝试；按本次审查要求Java总共3次、退避5/30秒，列为D01。原管理员failed job重试保留，但已取代/取消/越权前台拒绝，列为D03，不删掉旧测试掩盖差异。
- 原订单详情是`GET /orders/{order_number}`，工具也用order_number。已修正计划中不准确的long orderId服务签名，HTTP和工具继续使用订单号。
- 原订单金额列DECIMAL(12,2)，Pydantic输出`"12.30"`；已离线验证DTO时间零微秒无小数、非零固定六位Z，Java按实际格式适配。

## 产品语义修订

普通问答默认继续当前话题，不逐条LLM分类。话题切换、取消前台回答和取消后台报告分别处理；后台报告可写回非活跃的所属话题。换订单可以同topic新run，但清空旧目标槽位；上下文只用符合白名单的完整持久消息与重验事实，排除partial/失败/取消指令。

草稿来源与Agent执行确认分开：原有效业务草稿可以由客户主动查看并明确确认；不能因切话题自动迁移、自动确认。后台HITL绑定自己的run/revision/SQL/授权/预算，不依赖当前前台epoch，也不能被新消息冒充同意。

## 证据与尚未完成事项

本轮实际执行：源码核对、官方文档核对、Python离线OpenAPI导出、合成DTO金额/时间检查、文档交叉检查。没有修改运行数据库、安装Java依赖或运行Java产品测试。源OpenAPI SHA256见接口矩阵，不能把本记录中的勾选作为工程完成证据。

下一实施仍沿用用户已选的当前会话逐项实现，读取修订版计划与三个规范后建立隔离worktree。Java完成后再进入独立InsightPilot实施；后者不提前出现在简历完成项中。
