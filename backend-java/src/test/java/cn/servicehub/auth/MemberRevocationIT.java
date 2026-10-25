package cn.servicehub.auth;

import cn.servicehub.common.Actor;
import cn.servicehub.support.HttpIntegrationApp;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberRevocationIT {
    final HttpIntegrationApp app=new HttpIntegrationApp();
    @BeforeAll void start() throws Exception {app.start();}
    @AfterAll void stop(){app.close();}
    @BeforeEach void reset(){app.resetAuth();app.db.update("UPDATE conversations SET title='original' WHERE id=3001");}
    @Test void disabledUserCannotReuseCookie() throws Exception {
        var target=app.login(2002);var admin=app.login(2001);
        var r=app.request("PATCH","/api/v1/admin/members/2002",Map.of("is_active",false),admin);assertThat(r.statusCode()).isEqualTo(200);
        assertThat(app.request("GET","/api/v1/auth/me",null,target).statusCode()).isEqualTo(401);
        assertThat(app.db.queryForObject("SELECT session_version FROM users WHERE id=2002",Long.class)).isEqualTo(2);
    }
    @Test void persistedRevocationWinsWhenRedisNotificationIsLost() throws Exception {
        var target=app.login(2002);
        String token=target.cookie().split("=",2)[1];
        var redis=app.context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        String key="shj:session:"+SessionStore.digest(token);
        assertThat(redis.hasKey(key)).isTrue();
        // Commit revocation without invoking the Redis invalidation callback.
        app.db.update("UPDATE users SET is_active=0,session_version=session_version+1 WHERE id=2002");
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(app.request("GET","/api/v1/auth/me",null,target).statusCode()).isEqualTo(401);
    }
    @Test void roleChangeInvalidatesPreviouslyAuthenticatedSession() throws Exception {
        app.db.update("UPDATE users SET role='admin' WHERE id=2002");
        var target=app.login(2002);var admin=app.login(2001);
        assertThat(app.request("PATCH","/api/v1/admin/members/2002",Map.of("role","agent"),admin).statusCode()).isEqualTo(200);
        assertThat(app.request("GET","/api/v1/auth/me",null,target).statusCode()).isEqualTo(401);
    }
    @Test void revocationCommittedBeforeBusinessLockPreventsBusinessMutation() throws Exception {
        var stale=new Actor(1001,2002,Role.AGENT,1);var guard=app.requiredBean("cn.servicehub.auth.AuthorizationService");
        var admin=app.login(2001);
        assertThat(app.request("PATCH","/api/v1/admin/members/2002",Map.of("is_active",false),admin).statusCode()).isEqualTo(200);
        assertThatThrownBy(()->tx().executeWithoutResult(status->{requireForWrite(guard,stale);app.db.update("UPDATE conversations SET title='forbidden' WHERE id=3001");}))
            .isInstanceOf(cn.servicehub.common.BusinessException.class);
        assertThat(app.db.queryForObject("SELECT title FROM conversations WHERE id=3001",String.class)).isEqualTo("original");
    }
    @Test void businessHoldingAuthorizationLockCommitsBeforeRevocationCanCommit() throws Exception {
        var guard=app.requiredBean("cn.servicehub.auth.AuthorizationService");var actor=new Actor(1001,2002,Role.AGENT,1);var admin=app.login(2001);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var revokeStarted=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var business=pool.submit(()->tx().executeWithoutResult(s->{requireForWrite(guard,actor);locked.countDown();await(release);app.db.update("UPDATE conversations SET title='permitted-before-revoke' WHERE id=3001");}));
            assertThat(locked.await(3,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{revokeStarted.countDown();return app.request("PATCH","/api/v1/admin/members/2002",Map.of("is_active",false),admin);});
            try {
                assertThat(revokeStarted.await(3,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->revoke.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }finally{release.countDown();}
            business.get(5,TimeUnit.SECONDS);assertThat(revoke.get(5,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        assertThat(app.db.queryForObject("SELECT title FROM conversations WHERE id=3001",String.class)).isEqualTo("permitted-before-revoke");
        assertThat(app.db.queryForObject("SELECT is_active FROM users WHERE id=2002",Boolean.class)).isFalse();
        assertThatThrownBy(()->tx().executeWithoutResult(s->requireForWrite(guard,actor))).isInstanceOf(cn.servicehub.common.BusinessException.class);
    }
    private TransactionTemplate tx(){return new TransactionTemplate(app.context.getBean(PlatformTransactionManager.class));}
    private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Test latch timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private static void requireForWrite(Object service,Actor actor){
        try{service.getClass().getMethod("requireForWrite",Actor.class).invoke(service,actor);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError("Authorization write guard is absent",e);}
    }
}
