package cn.servicehub.support;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Real TCP proxy that discards an actual broker basic.ack confirm frame, never the published message. */
final class AmqpFrameProxy implements AutoCloseable {
 final ServerSocket listener;final String host;final int port;final ExecutorService threads=Executors.newVirtualThreadPerTaskExecutor();final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
 final AtomicInteger dropNext=new AtomicInteger(),droppedConfirms=new AtomicInteger();
 AmqpFrameProxy(String host,int port)throws IOException{
  this.host=host;this.port=port;listener=new ServerSocket();listener.bind(new InetSocketAddress("127.0.0.1",0));threads.submit(()->{while(!listener.isClosed())try{bridge(listener.accept());}catch(IOException ignored){}});
 }
 int localPort(){return listener.getLocalPort();}
 void bridge(Socket client)throws IOException{
  var broker=new Socket(host,port);client.setTcpNoDelay(true);broker.setTcpNoDelay(true);sockets.add(client);sockets.add(broker);
  threads.submit(()->{try{client.getInputStream().transferTo(broker.getOutputStream());}catch(IOException ignored){}finally{close(client);close(broker);}});
  threads.submit(()->{try{
   var input=new DataInputStream(broker.getInputStream());var output=client.getOutputStream();
   while(!client.isClosed()){
    byte[] header=input.readNBytes(7);if(header.length!=7)break;int size=((header[3]&255)<<24)|((header[4]&255)<<16)|((header[5]&255)<<8)|(header[6]&255);
    if(size<0||size>4*1024*1024)throw new IOException("AMQP fixture frame exceeds bound");byte[] payload=input.readNBytes(size+1);if(payload.length!=size+1)break;
    boolean ack=header[0]==1&&size>=4&&(payload[0]&255)==0&&(payload[1]&255)==60&&(payload[2]&255)==0&&(payload[3]&255)==80;
    if(ack&&dropNext.getAndUpdate(value->Math.max(0,value-1))>0){droppedConfirms.incrementAndGet();continue;}
    output.write(header);output.write(payload);output.flush();
   }
  }catch(IOException ignored){}finally{close(client);close(broker);}});
 }
 void close(Socket socket){sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
 @Override public void close(){try{listener.close();}catch(IOException ignored){}for(var socket:sockets)close(socket);threads.shutdownNow();}
}
