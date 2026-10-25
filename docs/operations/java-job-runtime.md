# Java 任务运行时

本文件对应迁移计划 Task 6。任务调度基础设施已实现；知识导入与 AI 回答的业务 handler 分别在 Task 8、10 接入。当前不能据此宣称完整 Java 客服已经上线。

## 提交与投递

业务事务同时保存 job、run（前台任务）和 outbox。消息只含 job_id、schema_version=1；消费端从数据库读取租户、发起人及原始 payload，不接受消息体传入权限。

Outbox 先取得 owner/lease_version，再在事务外发布持久消息到 shj.jobs。shj.ai、shj.ingest 和各自 DLQ 均为持久 quorum 队列。发布使用 mandatory + correlated confirm，确认 ack 且没有 return 才能通过 owner/version/未过期租约条件标记 dispatched_at。确认丢失允许重复发布；过期发布者不能覆盖新租约。

发布连接与消费连接分开。TCP、握手和 channel RPC 超时各 3 秒，confirm 等待 3 秒。NIO 每个写帧入队最多等待 1 秒；这不是整个发布动作的硬截止时间，也不表示供应商或 broker 物理只执行一次。

## 执行、恢复和确认

执行槽先取得，之后才能领取 job；没有槽位时持久延后 1 秒并产生新 outbox，不消耗执行尝试。领取通过数据库状态条件更新，execution_version 每次成功领取递增。重复消息不创建新 job，也不增加 attempts。

数据库锁顺序为当前用户、会话/文档、run、job。提交结果必须仍满足 owner、execution_version、budget_revision、RUNNING、lease_expires_at 和 deadline_at；条件更新不是 1 行则调用方业务事务回滚。领域 handler 必须在保存业务结果的同一事务调用 finish，不能先保存回答再单独更新 job。

生产租约 60 秒，每 10 秒续期。回收、投递、数据库租约续期、Redis 资源续期独立调度。过期而未被重新领取的旧 Worker 同样不能写入。恢复保留首次 started_at/deadline_at，不能靠重试延长硬预算。

每批最多 3 次执行尝试；前两次失败后分别退避 5、30 秒。取消及永久错误不应自动重试。消费采用 manual ack，终态/重试/延后的数据库事务完成后才 ack；不能持久化时不 ack。未知任务安全 ack，畸形或额外携带租户字段的协议拒绝进入 DLQ。

## 隔离与预算

所有 Worker 共用 Redis 原子准入：总执行 4、后台最多 3；模型调用另设总 4、后台最多 3。前台 listener 允许 4 个消费者，后台 3 个，prefetch=1；这是消费线程数，有效执行仍受总 4 的共享限制。后台不能占用为前台保留的一个执行和一个模型名额。

前台排队最多 60 秒，首次领取后整轮硬截止 120 秒；知识导入排队/执行分别 600 秒。数据库时间用于持久租约和最终写入判断。模型等待、工具执行、重试和恢复均须服从原截止时间，模型请求自身还需取 45 秒与剩余时间的较小值。

前台检查会话状态、epoch、当前 topic 与 run。知识导入绑定 document_id/version_id/document_revision，不绑定当前聊天 epoch。切换聊天不能取消合法后台导入；发起人被禁用、降权或文档版本失效则不能激活结果。

## 验证边界

集成测试使用独立真实 MySQL/Redis/RabbitMQ 容器和合成身份。Outbox 用例在真实发布成功后注入确认边界丢失/迟到，另有真实 broker 暂停恢复、真实 Worker profile 消费与 DLQ 测试。业务 handler 的测试替身仅在测试源码中注册。

完整双 API JVM、Worker 进程退出、数据库中断、真实模型与前端契约的系统验收属于 Task 13，不能用本阶段的集成用例代替。
