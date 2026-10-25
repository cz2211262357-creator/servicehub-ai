# Java 迁移接口契约清单与通过标准

日期：2026-10-22。状态：冻结清单和 Java 运行时契约已实现，正在合并回归与在线验收；见 java-acceptance.md。

## 冻结来源

通过现有 `servicehub.main.create_app().openapi()` 导出，不连接数据库，不运行模型，不读取业务数据。下面的 39 项是原接口；框架 `/docs` 等页面不计数。Schema 基线为同目录 `servicehub-python-openapi-2026-10-22.json`。

基线 SHA256：`1a66dbcdce53dfed0af857486aa1a6f58c01d6c6f1c434b81d473ad57fa37b17`。

| ID | 原方法与路径 | 路径/查询参数 | 请求体 Schema | OpenAPI 响应码 |
|---|---|---|---|---|
| H01 | GET `/api/v1/admin/audits` | cursor,limit,action,outcome | — | 200,422 |
| H02 | GET `/api/v1/admin/jobs` | cursor,limit,state | — | 200,422 |
| H03 | POST `/api/v1/admin/jobs/{job_id}/retry` | job_id | — | 200,422 |
| H04 | GET `/api/v1/admin/members` | cursor,limit | — | 200,422 |
| H05 | POST `/api/v1/admin/members` | — | MemberInput | 201,422 |
| H06 | PATCH `/api/v1/admin/members/{member_id}` | member_id | MemberUpdate | 200,422 |
| H07 | GET `/api/v1/admin/metrics/summary` | start,end | — | 200,422 |
| H08 | GET `/api/v1/admin/runtime` | — | — | 200 |
| H09 | GET `/api/v1/after-sales` | cursor,limit,status | — | 200,422 |
| H10 | GET `/api/v1/after-sales/drafts` | — | — | 200 |
| H11 | POST `/api/v1/after-sales/drafts` | — | DraftInput | 201,422 |
| H12 | POST `/api/v1/after-sales/drafts/{draft_id}/confirm` | draft_id | ConfirmInput | 200,422 |
| H13 | GET `/api/v1/after-sales/{ticket_id}` | ticket_id | — | 200,422 |
| H14 | PATCH `/api/v1/after-sales/{ticket_id}/status` | ticket_id | TransitionInput | 200,422 |
| H15 | POST `/api/v1/auth/login` | — | LoginInput | 200,422 |
| H16 | POST `/api/v1/auth/logout` | — | — | 204 |
| H17 | GET `/api/v1/auth/me` | — | — | 200 |
| H18 | GET `/api/v1/conversations` | cursor,limit,status,search | — | 200,422 |
| H19 | POST `/api/v1/conversations` | — | ConversationInput | 201,422 |
| H20 | GET `/api/v1/conversations/{conversation_id}` | conversation_id | — | 200,422 |
| H21 | POST `/api/v1/conversations/{conversation_id}/claim` | conversation_id | ClaimInput | 200,422 |
| H22 | POST `/api/v1/conversations/{conversation_id}/close` | conversation_id | — | 200,422 |
| H23 | GET `/api/v1/conversations/{conversation_id}/context` | conversation_id | — | 200,422 |
| H24 | GET `/api/v1/conversations/{conversation_id}/events` | conversation_id,after_seq,limit | — | 200,422 |
| H25 | POST `/api/v1/conversations/{conversation_id}/handoff` | conversation_id | — | 200,422 |
| H26 | GET `/api/v1/conversations/{conversation_id}/messages` | conversation_id,cursor,limit | — | 200,422 |
| H27 | POST `/api/v1/conversations/{conversation_id}/messages` | conversation_id | MessageInput | 200,422 |
| H28 | GET `/api/v1/health/live` | — | — | 200 |
| H29 | GET `/api/v1/health/ready` | — | — | 200 |
| H30 | GET `/api/v1/knowledge/documents` | cursor,limit | — | 200,422 |
| H31 | POST `/api/v1/knowledge/documents` | — | Body_upload_api_v1_knowledge_documents_post | 201,422 |
| H32 | DELETE `/api/v1/knowledge/documents/{document_id}` | document_id | — | 204,422 |
| H33 | GET `/api/v1/knowledge/documents/{document_id}` | document_id | — | 200,422 |
| H34 | PATCH `/api/v1/knowledge/documents/{document_id}` | document_id | EnableInput | 200,422 |
| H35 | GET `/api/v1/knowledge/documents/{document_id}/download` | document_id,version_id | — | 200,422 |
| H36 | POST `/api/v1/knowledge/documents/{document_id}/versions` | document_id | Body_version_api_v1_knowledge_documents__document_id__versions_post | 201,422 |
| H37 | GET `/api/v1/metrics` | — | — | 200 |
| H38 | GET `/api/v1/orders` | cursor,limit,customer_id,search | — | 200,422 |
| H39 | GET `/api/v1/orders/{order_number}` | order_number | — | 200,422 |

OpenAPI 中列出的响应码并不穷尽运行时的认证、业务冲突和依赖故障。下面的运行时矩阵同样是契约的一部分，不能仅对比 OpenAPI 文本或统计路由数。

## 新增 3 项（单独验收，不拿来替代旧接口）

| ID | 方法与路径 | 输入 | 验收重点 |
|---|---|---|---|
| N01 | POST `/api/v1/conversations/{conversation_id}/interrupt` | expected_generation_epoch | CSRF、权限、幂等停止、旧 epoch 重试不取消新执行 |
| N02 | GET `/api/v1/conversations/{conversation_id}/topics` | conversation_id | 仅同会话授权话题、active_topic_id、最多 100 个 |
| N03 | POST `/api/v1/conversations/{conversation_id}/topics/switch` | mode/topic_id?/expected_generation_epoch/pending_message_id? | CAS、A→B→A、跨会话/租户拒绝、待分配问题只解析一次 |

## 每项 HTTP 必须核对的维度

用同一组合成种子数据分别调用 Python 与 Java（不同隔离库），按 H01–H39 记录测试用例 ID 和结果。只规范化显式非确定字段：各实现独立产生的 ID 通过测试映射关联，request_id 只断言非空/回显规则，时间用固定时钟或与被创建记录相同的精确值比较；不能全局删除时间、金额或错误码来获得假通过。

- 方法、路径、path/query/body、默认值、长度/边界、必填与可空、分页游标逐项一致；非法 JSON/字段按原运行行为冻结，不能仅依赖 Spring 默认异常。
- 成功 HTTP 状态、字段集合、snake_case、enum、null/省略规则、嵌套结构逐项验证。新 topic 字段允许明确的加法扩展，旧字段类型不能改变。
- 登录 Cookie 的名字允许 Java 独立名称；HttpOnly、SameSite、Secure、Path、TTL 和 credentials 行为逐项记录。CSRF 匹配会话，对所有需要保护的写请求验证缺失/错误；登录/退出特殊规则按原实现样本确认。
- 使用匿名、customer、agent、admin、同租户不同客户、另一租户六类身份，对每个受保护接口验证允许与拒绝；未授权错误不泄露对象存在性。401/403/404/409/422/429 依具体业务触发，error.code/message/request_id 与可选 details、Retry-After、204 无响应体逐项比较。
- 金额保持 JSON 字符串和两位小数，例如 `"12.30"`，不能变浮点数字；DECIMAL(12,2) 的有效范围、溢出拒绝、退款/价格不为负等沿业务模型验证。日期使用UTC ISO8601，零微秒无小数、非零固定六位，例如`2026-10-22T00:00:00.123000Z`；null规则沿基线样本；不能随 JVM 时区改变。
- multipart 的字段名、媒体类型、10 MiB 边界、上传错误与私有下载的 Content-Type/Content-Disposition、本人历史引用鉴权单独验证。
- 业务特有错误覆盖：消息幂等冲突、会话已关闭、抢单版本过期、售后草稿过期/已消费、非法工单状态迁移、知识版本过期、任务不可重试；每项关联到 Hxx。

## WebSocket 契约 W01

路径 `/api/v1/conversations/{conversation_id}/live?after_seq=N`。冻结现有事件 kind、payload、持久 seq 与 transient seq=null、generation_epoch、心跳、重连和关闭码；新增 topic_id/run_id/execution_version 为显式扩展。

验证 Origin/Cookie 身份、4401/4403/1013、撤销身份、after_seq 边界/回放去重/序号缺口、128 缓冲和 5 秒发送超时。A→B→A 时 epoch 和 execution_version 旧的 delta 不显示；正式消息仅来自持久 message.created。断线补放和 Redis 通知丢失后结果一致，不能用 HTTP 测试替代 W01。

## 通过门槛

H01–H39、N01–N03、W01 必须逐项有对应可执行测试和机器结果；业务相关维度不能标作 N/A。全部测试通过、无未解释的字段差异、无跨租户数据、无未授权副作用；401/403 等被设计要求的拒绝算通过，其余未声明 5xx 算失败。基线加密摘要必须一致，更新基线要说明真实行为差异，不能为让 Java 通过而静默重录。

OpenAPI 只是结构基线；运行时样本和差异报告在实施任务 2/13 中生成。本文件是验收约定，不表示这些运行时对比已经通过。


## 已声明的设计差异（不计作未解释差异）

| ID | 旧实现/原计划与修订版 | 验证要求 |
|---|---|---|
| D01 | 原Python最多4次自动执行，Java按本次审查改为总共3次、退避5/30秒，整轮有固定deadline；runtime.automatic_retries为2次额外重试 | 保留接口/错误结构；验证最大次数与截止，不重录基线掩盖变化 |
| D02 | 新增3个话题/停止接口、话题字段、消息不可变触发关联和waiting_input状态 | 旧字段/小写状态保留，新能力测试单列，前端同步类型 |
| D03 | 原admin retry可重排任何failed job；Java拒绝已取代/取消/越权的前台job，仅当前合法failed run允许显式重试；内部RETRY_WAIT/wire retrying（旧Python返回queued） | 仍返回同job_id；stale请求409 job_conflict；新budget_revision/token和审计明确，取消永不复活 |

以上是用户审查推动的行为修订，数量兼容不表示所有内部重试行为与Python完全相同。新增范围也不能成为删除H01–H39测试的理由。

D04：健康就绪和受保护 runtime 增加 RabbitMQ/各依赖状态；只新增安全状态名，不包含端点或凭据。
D05：JobDTO.payload 保留旧 document_version_id，新增来源 actor_id/auth_version/document_id/document_revision/version_id 等执行安全元数据；对照测试逐项验证来源归属，旧字段不删除。

D06：message.created 的会话快照在同一事务建立 run/job 后持久化，ai_reply 含实际 queued run（旧 Python 此时为 null）；字段类型不变。测试核对 run/会话/epoch 真实关联，前端按持久快照及时显示排队状态。
