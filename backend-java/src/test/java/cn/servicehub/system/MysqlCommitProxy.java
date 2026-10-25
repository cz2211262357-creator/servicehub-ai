package cn.servicehub.system;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only plaintext MySQL proxy: lose the client acknowledgement before forwarding COMMIT. */
final class MysqlCommitProxy implements AutoCloseable {
 private final ServerSocket listener;
 private final String host;private final int upstreamPort;
 private final ExecutorService threads=Executors.newVirtualThreadPerTaskExecutor();
 private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
 private final AtomicBoolean once=new AtomicBoolean();
 private final CountDownLatch captured=new CountDownLatch(1),release=new CountDownLatch(1),committed=new CountDownLatch(1);
 private volatile Throwable failure;private volatile boolean closed;
 MysqlCommitProxy(String host,int port) throws IOException {
  this.host=host;upstreamPort=port;listener=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));
  threads.submit(()->{while(!closed)try{var client=listener.accept();threads.submit(()->serve(client));}catch(IOException error){if(!closed)failure=error;}});
 }
 int port(){return listener.getLocalPort();}
 void releaseCommit(){release.countDown();}
 void awaitCommit() throws Exception {
  if(!captured.await(5,TimeUnit.SECONDS))throw new AssertionError("Actual COMMIT was not intercepted",failure);
  releaseCommit();
  if(!committed.await(15,TimeUnit.SECONDS))throw new AssertionError("Delayed COMMIT did not finish",failure);
  if(failure!=null)throw new AssertionError("Owned COMMIT proxy failed",failure);
 }
 private void serve(Socket client){
  Socket backend=null;var lost=new AtomicBoolean();var forwarded=new AtomicBoolean();
  try{
   sockets.add(client);backend=new Socket(host,upstreamPort);sockets.add(backend);var upstream=backend;
   threads.submit(()->{
    try{while(true){byte[] packet=read(upstream.getInputStream());if(packet==null)return;
      if(lost.get()){
       if(forwarded.get()){
        if(packet.length<5||packet[4]!=0)failure=new AssertionError("Delayed COMMIT must receive server OK");
        committed.countDown();return;
       }
      }else{client.getOutputStream().write(packet);client.getOutputStream().flush();}
    }}catch(IOException error){if(!closed&&!lost.get()&&!client.isClosed())failure=error;}
   });
   boolean marker=false;
   while(true){
    byte[] packet=read(client.getInputStream());if(packet==null)return;
    String query=packet.length>5&&packet[4]==3?new String(packet,5,packet.length-5,StandardCharsets.UTF_8):"";
    if(query.startsWith("INSERT INTO")&&query.contains("audit_logs")&&query.contains("migration.imported"))marker=true;
    if(marker&&query.strip().equalsIgnoreCase("COMMIT")&&once.compareAndSet(false,true)){
     lost.set(true);client.close();captured.countDown();
     if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("Owned delayed COMMIT release timed out");
     forwarded.set(true);upstream.getOutputStream().write(packet);upstream.getOutputStream().flush();
     if(!committed.await(15,TimeUnit.SECONDS))throw new AssertionError("Owned delayed COMMIT response timed out");
     return;
    }
    upstream.getOutputStream().write(packet);upstream.getOutputStream().flush();
   }
  }catch(Throwable error){if(!closed)failure=error;}
  finally{closeSocket(client);if(backend!=null)closeSocket(backend);}
 }
 private static byte[] read(InputStream input) throws IOException {
  byte[] header=input.readNBytes(4);if(header.length==0)return null;if(header.length!=4)throw new EOFException();
  int length=Byte.toUnsignedInt(header[0])|(Byte.toUnsignedInt(header[1])<<8)|(Byte.toUnsignedInt(header[2])<<16);
  byte[] payload=input.readNBytes(length);if(payload.length!=length)throw new EOFException();
  var packet=Arrays.copyOf(header,length+4);System.arraycopy(payload,0,packet,4,length);return packet;
 }
 private void closeSocket(Socket socket){sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
 @Override public void close(){closed=true;releaseCommit();try{listener.close();}catch(IOException ignored){}sockets.forEach(this::closeSocket);threads.shutdownNow();}
}
