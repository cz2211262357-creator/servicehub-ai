package cn.servicehub.ai;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class HttpCallCancellationTest {
    Object scope(){
        try{return Class.forName("cn.servicehub.ai.HttpCallCancellation").getConstructor().newInstance();}
        catch(ReflectiveOperationException e){return fail("Missing request-scoped transport cancellation",e);}
    }
    void invoke(Object scope,String method,Class<?>[] types,Object...values){
        try{scope.getClass().getMethod(method,types).invoke(scope,values);}
        catch(InvocationTargetException e){throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    @Test void cancelClosesOnlyItsBlockedHttpCall() throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var workers=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(workers);
        server.createContext("/slow",exchange->{try{entered.countDown();release.await(5,TimeUnit.SECONDS);exchange.sendResponseHeaders(200,1);exchange.getResponseBody().write(1);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.createContext("/fast",exchange->{try{exchange.sendResponseHeaders(200,1);exchange.getResponseBody().write(1);}finally{exchange.close();}});server.start();
        var client=new OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(10)).build();
        try(var pool=Executors.newSingleThreadExecutor()){
            Object scope=scope();Call first=client.newCall(new Request.Builder().url("http://127.0.0.1:"+server.getAddress().getPort()+"/slow").build());
            invoke(scope,"register",new Class<?>[]{Call.class},first);
            var result=pool.submit(()->{try(var response=first.execute()){return response.code();}});
            try{
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();invoke(scope,"cancel",new Class<?>[]{});
                assertThatThrownBy(()->result.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IOException.class);
                try(var response=client.newCall(new Request.Builder().url("http://127.0.0.1:"+server.getAddress().getPort()+"/fast").build()).execute()){assertThat(response.code()).isEqualTo(200);}
            }finally{release.countDown();}
        }finally{server.stop(0);workers.shutdownNow();client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
    }
    @Test void cancellationBeforeRegistrationRejectsLateCall(){
        Object scope=scope();invoke(scope,"cancel",new Class<?>[]{});
        var client=new OkHttpClient();Call call=client.newCall(new Request.Builder().url("http://127.0.0.1:1/unused").build());
        invoke(scope,"register",new Class<?>[]{Call.class},call);assertThat(call.isCanceled()).isTrue();
        client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();
    }
}
