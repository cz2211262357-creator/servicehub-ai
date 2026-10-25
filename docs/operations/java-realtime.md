# Java 会话实时连接

连接为 /api/v1/conversations/{conversation_id}/live?after_seq=N，使用登录 Cookie，Origin 必须明确位于配置名单；缺 Origin 也拒绝。WebSocket 不传 CSRF token，普通 HTTP 写请求继续使用 Cookie 与 CSRF。

为了让浏览器真正收到4401/4403，live升级路径单独进入 WebSocket handler，完成101握手后首先校验来源、Cookie及对象权限。通过前不建立业务订阅、不发送数据。来源不符/对象无权限为4403，登录无效为4401，依赖不可用为1013；此分支只允许 GET+Upgrade:websocket 的精确live路径，其他接口仍经原认证过滤器。HTTP升级前拒绝只能表现为握手失败，不能声称其自然变成自定义关闭码。

## 顺序、恢复与临时分片

API 的 Redis 监听器在应用启动时订阅 shj:conversation:*。每个已授权连接先登记订阅，再按数据库 seq > after_seq 升序补放；提示丢失时，最多2秒后身份复核与数据库补放仍会继续。持久事件只由数据库驱动，Redis通知不能创建正式消息。通知分发使用单个有界执行器，只解析并入队，不执行SQL或网络发送；每连接独立串行处理，防止同一channel分片在并发回调中换序。长历史逐条直接发送，201事件回放覆盖超过128的历史，不把全部历史塞入内存缓冲。

标准事件保留 kind/conversation_id/seq/generation_epoch/payload；增加 topic_id/run_id/execution_version。payload.conversation 包含原快照，ai_reply 另增加真实执行令牌 execution_version。临时 ai.delta 的 seq 为null，payload含text和run_id；heartbeat 的epoch为0。持久 conversation.updated 包含取消、失败与等待输入的快照，已有 ai.failed/ai.state 消费兼容保留。

Worker 本地 Spring事件经同一个 Redis channel 传递到其他 API 进程。发布和接收分别验证当前会话与执行身份；新话题、epoch、run、过期租约或旧execution_version分片均失效。前端再检查会话/topic/run/epoch/execution_version，并在换话题、停止和同run租约恢复时清空旧partial。

首次补放窗口内的瞬时分片可丢弃；断线也不回放临时分片。正式回答依赖持久message.created恢复，不把部分流存成已完成回答或后续模型上下文。

## 慢连接与退出

每个连接一个串行发送者，最多128个待发事件加一个正在发送的事件。临时事件溢出关闭1013；持久补放逐条等待实际发送完成。Tomcat BLOCKING_SEND_TIMEOUT 明确设5000L，另有独立watchdog检查发送硬期限。客户端无法接收关闭帧时可能看到1006，重连仍从最后durable seq补放。

身份在每次补放/临时事件处理及默认2秒轮询时重新读取Cookie会话与用户权限，退出、过期和撤权会关闭现有连接。关闭先发送关闭帧，再结束发送者；普通关闭用closed标志结束数据库补放线程，不在JDBC读取中随意中断并损坏池连接。

浏览器重连延迟为1/2/4/8/15秒，收到有效事件后才重置。替换连接后，旧连接回调不能关闭新连接；页面切会话后，旧HTTP结果不能回写。停止/切换使用当前epoch做CAS，409后刷新并提示操作未应用。切换不自动重跑旧问题，待确认的问题通过pending_message_id显式归属。

## 类型与验证

运行项目Python执行 scripts/export-java-contract.py，再运行 npm --prefix frontend run generate:api。导出以冻结Python OpenAPI为基础，叠加三个接口与话题/执行字段；不改旧基线。运行时参数、权限、错误及事件兼容性由契约测试另行检查。

LiveReplayIT 使用实际JDK WebSocket客户端覆盖握手、Cookie/Origin/租户、回放、丢通知、登出、真实Redis暂停和fenced分片；另以可控阻塞传输证明5秒watchdog，LiveBufferTest验证128缓冲上界。它们不替代后续两个API JVM与独立Worker的整体验收。
