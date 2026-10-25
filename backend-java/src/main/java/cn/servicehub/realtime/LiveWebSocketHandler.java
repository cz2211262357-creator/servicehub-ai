package cn.servicehub.realtime;
import cn.servicehub.config.ServiceHubProperties;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
@Component
public class LiveWebSocketHandler extends TextWebSocketHandler {
 private final LiveEventHub hub;private final ServiceHubProperties properties;
 public LiveWebSocketHandler(LiveEventHub hub,ServiceHubProperties properties){this.hub=hub;this.properties=properties;}
 @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception{
  String origin=session.getHandshakeHeaders().getFirst("Origin");
  if(origin==null||!properties.allowedOrigins().contains(origin)){session.close(new CloseStatus(4403));return;}
  try{
   var uri=Objects.requireNonNull(session.getUri());String path=uri.getPath();
   if(!path.matches("/api/v1/conversations/[1-9][0-9]*/live"))throw new IllegalArgumentException();
   long conversation=Long.parseLong(path.split("/")[4]);var query=UriComponentsBuilder.fromUri(uri).build().getQueryParams();String after=query.getFirst("after_seq");
   if(query.keySet().stream().anyMatch(k->!k.equals("after_seq"))||query.containsKey("after_seq")&&query.get("after_seq").size()!=1||after!=null&&!after.matches("[0-9]{1,19}"))throw new IllegalArgumentException();
   long seq=after==null?0:Long.parseLong(after);String token=null;
   for(String header:session.getHandshakeHeaders().getOrEmpty("Cookie"))for(String part:header.split(";")){
    String[] pair=part.strip().split("=",2);if(pair.length==2&&pair[0].equals(properties.sessionCookieName())){if(token!=null)throw new IllegalArgumentException();token=pair[1];}
   }
   if(session instanceof NativeWebSocketSession nativeSocket){
    var nativeSession=nativeSocket.getNativeSession(jakarta.websocket.Session.class);
    if(nativeSession!=null)nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT",5000L);
   }
   hub.connect(session,token,conversation,seq);
  }catch(IllegalArgumentException e){session.close(new CloseStatus(4403));}
 }
 @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) throws Exception{session.close(CloseStatus.POLICY_VIOLATION);}
 @Override public void handleTransportError(WebSocketSession session,Throwable error){hub.disconnect(session.getId(),1013);}
 @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){hub.disconnect(session.getId(),status.getCode());}
}
