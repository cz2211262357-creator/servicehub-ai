package cn.servicehub.support;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
/** Owns a real JDK WebSocket, including HTTP upgrade, frame flow control and bounded reads. */
public final class ContractSocket implements WebSocket.Listener,AutoCloseable {
 private final HttpIntegrationApp app;private final BlockingQueue<String> frames=new LinkedBlockingQueue<>();private final StringBuilder partial=new StringBuilder();
 private final CompletableFuture<Integer> closed=new CompletableFuture<>();private final WebSocket socket;
 public ContractSocket(HttpIntegrationApp app,long room,long after,String cookie,String origin)throws Exception{
  this.app=app;var builder=app.http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(4));if(cookie!=null)builder.header("Cookie",cookie);if(origin!=null)builder.header("Origin",origin);
  socket=builder.buildAsync(URI.create("ws://127.0.0.1:"+app.context.getEnvironment().getProperty("local.server.port")+"/api/v1/conversations/"+room+"/live?after_seq="+after),this).get(5,TimeUnit.SECONDS);
 }
 public CompletableFuture<Integer> closeCode(){return closed;}
 public JsonNode next(long milliseconds)throws InterruptedException{String value=frames.poll(milliseconds,TimeUnit.MILLISECONDS);return value==null?null:app.json.readTree(value);}
 public JsonNode until(String kind,int seconds)throws InterruptedException{
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<end){var next=next(100);if(next!=null&&next.path("kind").asString().equals(kind))return next;}return fail("Actual socket event not delivered: "+kind);
 }
 @Override public void onOpen(WebSocket socket){socket.request(1);}
 @Override public CompletionStage<?> onText(WebSocket socket,CharSequence value,boolean last){partial.append(value);if(last){frames.add(partial.toString());partial.setLength(0);}socket.request(1);return null;}
 @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason){closed.complete(code);return null;}
 @Override public void onError(WebSocket socket,Throwable error){closed.completeExceptionally(error);}
 @Override public void close(){if(!socket.isOutputClosed())socket.sendClose(1000,"");}
}
