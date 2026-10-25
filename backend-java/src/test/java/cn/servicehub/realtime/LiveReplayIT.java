package cn.servicehub.realtime;
import cn.servicehub.ai.SupportAgent;
import cn.servicehub.conversations.*;
import cn.servicehub.jobs.*;
import cn.servicehub.support.HttpIntegrationApp;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveReplayIT {
    final HttpIntegrationApp app=new HttpIntegrationApp();final List<Wire> sockets=new ArrayList<>();final List<ExecutionPermit> permits=new ArrayList<>();
    HttpIntegrationApp.Login customer;long room;
    @BeforeAll void start() throws Exception{app.start();}
    @BeforeEach void reset() throws Exception{app.resetAuth();customer=app.login(2003);room=app.body(app.request("POST","/api/v1/conversations",Map.of(),customer)).path("id").asLong();}
    @AfterEach void disconnect(){sockets.forEach(Wire::close);sockets.clear();permits.forEach(ExecutionPermit::close);permits.clear();}
    @AfterAll void close(){app.close();}
    JsonNode send() throws Exception{var response=app.request("POST","/api/v1/conversations/"+room+"/messages",Map.of("client_message_id",UUID.randomUUID().toString(),"body","查询订单 ORD-A-001"),customer);assertThat(response.statusCode()).isEqualTo(200);return app.body(response);}
    Wire open(long id,long after,HttpIntegrationApp.Login login,String origin) throws Exception{
        var wire=new Wire();var b=app.http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(4));
        if(login!=null)b.header("Cookie",login.cookie());if(origin!=null)b.header("Origin",origin);
        wire.socket=b.buildAsync(URI.create("ws://127.0.0.1:"+app.context.getEnvironment().getProperty("local.server.port")+"/api/v1/conversations/"+id+"/live?after_seq="+after),wire).get(5,TimeUnit.SECONDS);sockets.add(wire);return wire;
    }
    JsonNode event(Wire wire,Predicate<JsonNode> predicate) throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(System.nanoTime()<end){String raw=wire.text.poll(100,TimeUnit.MILLISECONDS);if(raw!=null){var event=app.json.readTree(raw);if(predicate.test(event))return event;}}
        return fail("Expected bounded live event was not delivered; close="+wire.closed.getNow(null));
    }
    @Test void afterSeqReplaysInOrderAndRecoversWithoutOutboxNotification() throws Exception{
        var message=send();var wire=open(room,0,customer,"http://localhost:8081");
        var first=event(wire,e->e.path("seq").isNumber());assertThat(first.path("seq").asLong()).isEqualTo(1);
        var saved=event(wire,e->e.path("payload").path("message").path("id").asLong()==message.path("id").asLong());long cursor=saved.path("seq").asLong();
        assertThat(saved.path("payload").path("conversation").path("id").asLong()).isEqualTo(room);
        wire.close();var next=send();var resumed=open(room,cursor,customer,"http://localhost:8081");
        assertThat(event(resumed,e->e.path("payload").path("message").path("id").asLong()==next.path("id").asLong()).path("seq").asLong()).isGreaterThan(cursor);
        var later=send();assertThat(event(resumed,e->e.path("payload").path("message").path("id").asLong()==later.path("id").asLong()).path("seq").asLong()).isGreaterThan(cursor);
    }
    @Test void unauthenticatedSocketCloses4401BeforeAnyBusinessEvent() throws Exception{
        var wire=open(room,0,null,"http://localhost:8081");assertThat(wire.closed.get(5,TimeUnit.SECONDS)).isEqualTo(4401);assertThat(wire.text).isEmpty();
    }
    @Test void forbiddenOriginIncludingMissingOriginCloses4403() throws Exception{
        for(String origin:Arrays.asList("https://untrusted.example",null)){
            var wire=open(room,0,customer,origin);assertThat(wire.closed.get(5,TimeUnit.SECONDS)).isEqualTo(4403);assertThat(wire.text).isEmpty();
        }
    }
    @Test void foreignTenantCannotSubscribe() throws Exception{
        var wire=open(room,0,app.login(5001),"http://localhost:8081");assertThat(wire.closed.get(5,TimeUnit.SECONDS)).isEqualTo(4403);assertThat(wire.text).isEmpty();
    }
    @Test void logoutRevokesAnAlreadyConnectedSocket() throws Exception{
        var wire=open(room,0,customer,"http://localhost:8081");event(wire,e->e.path("seq").asLong()==1);
        assertThat(app.request("POST","/api/v1/auth/logout",null,customer).statusCode()).isEqualTo(204);
        assertThat(wire.closed.get(5,TimeUnit.SECONDS)).isEqualTo(4401);
    }
    @Test void lostCacheCloses1013AndNeverFallsBackToAnonymousAccess() throws Exception{
        var wire=open(room,0,customer,"http://localhost:8081");event(wire,e->e.path("seq").asLong()==1);
        var docker=app.dependencies.redis.getDockerClient();String id=app.dependencies.redis.getContainerId();docker.pauseContainerCmd(id).exec();
        try{assertThat(wire.closed.get(7,TimeUnit.SECONDS)).isEqualTo(1013);}finally{docker.unpauseContainerCmd(id).exec();}
    }
    @Test void transientReplyCarriesAllFencesAndStaleTopicFragmentIsDropped() throws Exception{
        var message=send();String owner="live-"+UUID.randomUUID();permits.add(app.context.getBean(AdmissionController.class).tryAcquire(ExecutionClass.INTERACTIVE,owner).orElseThrow());
        var lease=app.context.getBean(JobCoordinator.class).claim(message.path("trigger_job_id").asLong(),owner).orElseThrow();
        var fence=app.context.getBean(TopicService.class).resolve(lease,message.path("id").asLong(),TopicDecision.of("CONTINUE",null,false)).orElseThrow();
        long seq=app.body(app.request("GET","/api/v1/conversations/"+room,null,customer)).path("last_event_seq").asLong();
        var wire=open(room,seq,customer,"http://localhost:8081");
        event(wire,e->e.path("kind").asString().equals("heartbeat"));
        app.context.publishEvent(new SupportAgent.StreamEvent(fence,"合法分片"));
        var delta=event(wire,e->e.path("kind").asString().equals("ai.delta"));
        assertThat(delta.path("topic_id").asLong()).isEqualTo(fence.topicId());assertThat(delta.path("run_id").asLong()).isEqualTo(fence.runId());assertThat(delta.path("execution_version").asLong()).isEqualTo(lease.executionVersion());assertThat(delta.get("seq").isNull()).isTrue();
        assertThat(app.request("POST","/api/v1/conversations/"+room+"/interrupt",Map.of("expected_generation_epoch",fence.generationEpoch()),customer).statusCode()).isEqualTo(200);
        event(wire,e->e.path("generation_epoch").asLong()>fence.generationEpoch());
        app.context.publishEvent(new SupportAgent.StreamEvent(fence,"失效分片"));
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500);while(System.nanoTime()<end){String raw=wire.text.poll(50,TimeUnit.MILLISECONDS);if(raw!=null)assertThat(raw).doesNotContain("失效分片");}
    }

    @Test void blockedTransportCloses1013AtFiveSecondSendDeadline() throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new CompletableFuture<Integer>();var open=new java.util.concurrent.atomic.AtomicBoolean(true);
        var session=(org.springframework.web.socket.WebSocketSession)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.springframework.web.socket.WebSocketSession.class},(proxy,method,args)->{
            return switch(method.getName()){
                case "getId"->"blocked-"+room;case "isOpen"->open.get();
                case "sendMessage"->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}yield null;}
                case "close"->{open.set(false);closed.complete(((org.springframework.web.socket.CloseStatus)args[0]).getCode());yield null;}
                case "toString"->"SyntheticBlockedTransport";default->null;
            };
        });
        var hub=app.context.getBean(LiveEventHub.class);hub.connect(session,customer.cookie().split("=",2)[1],room,0);
        try{assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThat(closed.get(6,TimeUnit.SECONDS)).isEqualTo(1013);}
        finally{release.countDown();hub.disconnect(session.getId(),1000);}
    }
    @Test void replayLongerThanBufferUsesBackpressureAndKeepsEveryDurableSequence() throws Exception{
        var transactions=new org.springframework.transaction.support.TransactionTemplate(app.context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        transactions.executeWithoutResult(status->{for(int i=0;i<200;i++)app.context.getBean(EventStore.class).append(1001,room,"conversation.updated",Map.of("synthetic_index",i));});
        var wire=open(room,0,customer,"http://localhost:8081");
        for(long seq=1;seq<=201;seq++){long expected=seq;assertThat(event(wire,e->e.get("seq")!=null&&!e.get("seq").isNull()).path("seq").asLong()).isEqualTo(expected);}
        assertThat(wire.closed.isDone()).isFalse();
    }
    static final class Wire implements WebSocket.Listener,AutoCloseable {
        final BlockingQueue<String> text=new LinkedBlockingQueue<>();final CompletableFuture<Integer> closed=new CompletableFuture<>();final StringBuilder partial=new StringBuilder();WebSocket socket;
        @Override public void onOpen(WebSocket ws){ws.request(1);}
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){partial.append(data);if(last){text.add(partial.toString());partial.setLength(0);}ws.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason){closed.complete(status);return null;}
        @Override public void onError(WebSocket ws,Throwable error){closed.completeExceptionally(error);}
        @Override public void close(){if(socket!=null)socket.sendClose(1000,"");}
    }
}
