package cn.servicehub.realtime;
import cn.servicehub.ai.*;
import cn.servicehub.auth.*;
import cn.servicehub.common.BusinessException;
import cn.servicehub.conversations.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import tools.jackson.databind.json.JsonMapper;

/** Database replay owns durable messages; Redis carries wakeups and disposable fenced deltas. */
@Service
public class LiveEventHub implements MessageListener {
 private final AuthService auth;private final ConversationService conversations;private final ConversationMapper mapper;private final TopicService topics;
 private final MemberMapper members;private final LiveMapper live;private final StringRedisTemplate redis;private final JsonMapper json;
 private final ConcurrentMap<String,Subscriber> subscribers=new ConcurrentHashMap<>();
 private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("live-watchdog").factory());
 public LiveEventHub(AuthService auth,ConversationService conversations,ConversationMapper mapper,TopicService topics,MemberMapper members,LiveMapper live,StringRedisTemplate redis,JsonMapper json){
  this.auth=auth;this.conversations=conversations;this.mapper=mapper;this.topics=topics;this.members=members;this.live=live;this.redis=redis;this.json=json;
  watchdog.scheduleAtFixedRate(()->subscribers.values().forEach(s->{
   if(s.overdue())Thread.startVirtualThread(()->disconnect(s.session.getId(),1013));
   else if(System.nanoTime()-s.lastPoll>=TimeUnit.SECONDS.toNanos(2))s.tick();
  }),100,100,TimeUnit.MILLISECONDS);
 }
 public void connect(WebSocketSession session,String token,long conversation,long after){
  try{
   var actor=auth.authenticateSession(token).actor();auth.requireConversationAccess(actor,conversation);
   var sub=new Subscriber(session,token,actor.tenantId(),conversation,after);subscribers.put(session.getId(),sub);
   sub.tick();
  }catch(RuntimeException error){close(session,code(error));}
 }
 public void disconnect(String id,int status){
  var sub=subscribers.remove(id);if(sub==null)return;sub.closed.set(true);
  sub.sender.close(status);
 }
 @EventListener public void onAgentStream(SupportAgent.StreamEvent event){
  var f=event.fence();if(event.delta()==null||event.delta().isEmpty())return;
  publish(new LiveEvent("ai.delta",f.conversationId(),null,f.generationEpoch(),f.topicId(),f.runId(),f.executionVersion(),Map.of("text",event.delta(),"run_id",f.runId())));
 }
 public void publish(LiveEvent event){
  Long tenant=live.tenant(event.conversationId());if(tenant==null)return;
  if(event.seq()!=null){redis.convertAndSend(channel(tenant,event.conversationId()),json.writeValueAsString(Map.of("conversation_id",event.conversationId(),"seq",event.seq())));return;}
  if(!"ai.delta".equals(event.kind())||event.topicId()==null||event.runId()==null||event.executionVersion()==null||!(event.payload().get("text") instanceof String text)||text.isEmpty()||text.length()>16000)return;
  try{
   var row=mapper.get(tenant,event.conversationId());if(row==null)return;var member=members.byId(tenant,row.customerId());if(member==null)return;
   topics.requireReadable(member.actor(),new ExecutionFence(tenant,event.conversationId(),event.topicId(),event.runId(),event.generationEpoch(),event.executionVersion()));
   redis.convertAndSend(channel(tenant,event.conversationId()),json.writeValueAsString(event));
  }catch(ExecutionCancelledException e){/* An expired producer cannot emit a new current fragment. */}
  catch(BusinessException e){if(e.status()!=401&&e.status()!=403&&e.status()!=404)throw e;}
 }
 @Override public void onMessage(Message message,byte[] pattern){
  try{
   String[] parts=new String(message.getChannel(),StandardCharsets.UTF_8).split(":");if(parts.length!=4||!parts[0].equals("shj")||!parts[1].equals("conversation"))return;
   long tenant=Long.parseLong(parts[2]),conversation=Long.parseLong(parts[3]);var node=json.readTree(message.getBody());
   if(node.path("conversation_id").asLong()!=conversation)return;
   LiveEvent event=node.hasNonNull("seq")?null:json.readValue(message.getBody(),LiveEvent.class);
   for(var sub:subscribers.values())if(sub.tenant==tenant&&sub.conversation==conversation){
    if(event==null)sub.tick();else sub.delta(event);
   }
  }catch(RuntimeException e){/* Invalid transient data never replaces database replay. */}
 }
 private final class Subscriber {
  final WebSocketSession session;final String token;final long tenant,conversation;long cursor;long heartbeat;
  final AtomicBoolean closed=new AtomicBoolean(),tickQueued=new AtomicBoolean();final BufferedSender sender;
  volatile long networkStarted,lastPoll;
  Subscriber(WebSocketSession session,String token,long tenant,long conversation,long cursor){
   this.session=session;this.token=token;this.tenant=tenant;this.conversation=conversation;this.cursor=cursor;
   sender=new BufferedSender(this::process,status->{
    subscribers.remove(session.getId(),this);closed.set(true);Thread.startVirtualThread(()->close(session,status));
   });
  }
  void tick(){
   lastPoll=System.nanoTime();
   if(!closed.get()&&tickQueued.compareAndSet(false,true)&&!sender.offerHint(""))tickQueued.set(false);
  }
  void delta(LiveEvent event){if(!closed.get())sender.offer(json.writeValueAsString(event));}
  void process(String raw){
   if(closed.get())return;
   if(raw.isEmpty())tickQueued.set(false);
   try{
    var current=catchUp();
    if(!raw.isEmpty()){
     var event=json.readValue(raw,LiveEvent.class);var reply=current.aiReply();
     if("ai.delta".equals(event.kind())&&"AI_ACTIVE".equals(current.status())&&reply!=null&&"running".equals(reply.state())
       &&event.generationEpoch()==current.generationEpoch()&&Objects.equals(event.topicId(),current.activeTopicId())
       &&Objects.equals(event.runId(),reply.runId())&&Objects.equals(event.executionVersion(),reply.executionVersion()))write(raw);
    }
    if(heartbeat==0||System.nanoTime()-heartbeat>=TimeUnit.SECONDS.toNanos(10)){
     write(json.writeValueAsString(new LiveEvent("heartbeat",conversation,null,0,null,null,null,Map.of())));heartbeat=System.nanoTime();
    }
   }catch(RuntimeException e){disconnect(session.getId(),code(e));}
  }
  ConversationDtos.ConversationDto catchUp(){
   var actor=auth.authenticateSession(token).actor();if(actor.tenantId()!=tenant)throw SessionStore.unauthorized("登录已变化");
   auth.requireConversationAccess(actor,conversation);
   for(;;){
    var page=conversations.events(actor,conversation,cursor,100);
    for(var event:page.items()){write(json.writeValueAsString(durable(event)));cursor=event.seq();}
    if(!page.hasMore())break;
   }
   return conversations.get(actor,conversation);
  }
  void write(String text){
   if(closed.get()||!session.isOpen())throw new IllegalStateException("Live connection closed");
   networkStarted=System.nanoTime();
   try{session.sendMessage(new TextMessage(text));}catch(Exception e){throw new IllegalStateException("Live send failed");}
   finally{networkStarted=0;}
  }
  boolean overdue(){long started=networkStarted;return !closed.get()&&started!=0&&System.nanoTime()-started>TimeUnit.SECONDS.toNanos(5);}
 }
 @SuppressWarnings("unchecked") private LiveEvent durable(ConversationDtos.LiveEvent event){
  var data=event.payload();Long topic=null,run=null,execution=null;
  if(data.get("conversation") instanceof Map<?,?> snapshot){
   topic=number(snapshot.get("active_topic_id"));Object raw=snapshot.get("ai_reply");
   if(raw instanceof Map<?,?> reply){run=number(reply.get("run_id"));execution=number(reply.get("execution_version"));}
  }
  return new LiveEvent(event.kind(),event.conversationId(),event.seq(),event.generationEpoch(),topic,run,execution,data);
 }
 private Long number(Object value){return value instanceof Number n?n.longValue():null;}
 private String channel(long tenant,long conversation){return "shj:conversation:"+tenant+":"+conversation;}
 private int code(Throwable error){
  if(error instanceof BusinessException e){if(e.status()==401)return 4401;if(e.status()==403||e.status()==404)return 4403;}return 1013;
 }
 private void close(WebSocketSession session,int code){try{if(session.isOpen())session.close(new CloseStatus(code));}catch(Exception ignored){}}
 @PreDestroy public void stop(){watchdog.shutdownNow();List.copyOf(subscribers.keySet()).forEach(id->disconnect(id,1001));}

 /** One in-flight send plus at most128 pending events; no concurrent writes to a socket. */
 public static final class BufferedSender implements AutoCloseable {
  private final ArrayBlockingQueue<String> queue=new ArrayBlockingQueue<>(128);private final Consumer<String> send;private final IntConsumer close;
  private final AtomicBoolean closed=new AtomicBoolean();private final Thread drain;
  public BufferedSender(Consumer<String> send,IntConsumer close){this.send=send;this.close=close;drain=Thread.startVirtualThread(this::drain);}
  public boolean offer(String text){if(closed.get())return false;if(queue.offer(text))return !closed.get();close(1013);return false;}
  boolean offerHint(String text){return !closed.get()&&queue.offer(text);}
  private void drain(){
   try{while(!closed.get()){String text=queue.take();if(closed.get())return;send.accept(text);}}
   catch(InterruptedException e){Thread.currentThread().interrupt();}catch(RuntimeException e){close(1013);}
  }
  public void close(int status){if(closed.compareAndSet(false,true)){queue.clear();queue.offer("");close.accept(status);}}
  @Override public void close(){close(1000);}
 }
}
