package cn.servicehub.jobs;

import cn.servicehub.common.BusinessException;
import cn.servicehub.common.WireJson;
import cn.servicehub.support.DependencyContainers;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class JobJournalIT {
    private static final DependencyContainers dependencies=new DependencyContainers();
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    @BeforeAll static void start() {
        dependencies.start();
        dataSource=new DriverManagerDataSource(dependencies.jdbcUrl(),dependencies.username(),dependencies.password());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO tenants(id,name) VALUES(1001,'journal-a'),(1002,'journal-b')");
        jdbc.update("INSERT INTO users(id,tenant_id,email,display_name,password_hash,role) VALUES(2001,1001,'journal@example.test','Journal','synthetic','customer')");
        jdbc.update("INSERT INTO conversations(id,tenant_id,customer_id) VALUES(3001,1001,2001)");
    }
    @AfterEach void clean() { jdbc.update("DELETE FROM outbox_events"); jdbc.update("DELETE FROM jobs"); }
    @AfterAll static void stop() { dependencies.close(); }
    record Harness(AnnotationConfigApplicationContext context,TransactionTemplate tx,Object journal) implements AutoCloseable {
        @Override public void close(){context.close();}
    }
    private Harness harness() {
        Class<?> persistence=assertDoesNotThrow(()->Class.forName("cn.servicehub.config.PersistenceConfig"));
        Class<?> journalType=assertDoesNotThrow(()->Class.forName("cn.servicehub.jobs.JobJournal"));
        var context=new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class,()->dataSource);
        context.registerBean(JsonMapper.class,()->JsonMapper.builder().addModule(WireJson.module()).build());
        context.register(persistence,journalType);
        assertDoesNotThrow(context::refresh);
        return new Harness(context,new TransactionTemplate(context.getBean(PlatformTransactionManager.class)),context.getBean(journalType));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private Object command(long tenant,String key,String executionClass,Map<String,Object> payload) {
        return assertDoesNotThrow(()->{
            Class enumType=Class.forName("cn.servicehub.jobs.ExecutionClass");
            Object category=Enum.valueOf(enumType,executionClass);
            return Class.forName("cn.servicehub.jobs.JobCommand").getConstructor(long.class,String.class,enumType,String.class,Map.class)
                .newInstance(tenant,"INTERACTIVE".equals(executionClass)?"ai_reply":"knowledge_index",category,key,payload);
        });
    }
    private Object invoke(Object target,String method,Class<?>[] types,Object...args) {
        try{return target.getClass().getMethod(method,types).invoke(target,args);}
        catch(InvocationTargetException error){if(error.getCause() instanceof RuntimeException runtime)throw runtime;throw new AssertionError(error.getCause());}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private Object submit(Harness harness,Object command){return invoke(harness.journal(),"enqueue",new Class<?>[]{command.getClass()},command);}
    private long id(Object job){return ((Number)invoke(job,"id",new Class<?>[]{})).longValue();}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    static class DeliberateRollback extends RuntimeException { }
    @Test void rollbackRemovesJobAndOutbox(){
        try(var harness=harness()){
            var command=command(1001,"rollback","BACKGROUND",Map.of("document_version_id",11L));
            assertThatThrownBy(()->harness.tx().executeWithoutResult(status->{submit(harness,command);throw new DeliberateRollback();}))
                .isInstanceOf(DeliberateRollback.class);
            assertThat(count("jobs")).isZero();assertThat(count("outbox_events")).isZero();
        }
    }
    @Test void sameBusinessKeyKeepsOneJobAndInitialOutboxEvenAfterCompletion(){
        try(var harness=harness()){
            var command=command(1001,"same-business-key","BACKGROUND",Map.of("document_version_id",11L));
            Object first=harness.tx().execute(status->submit(harness,command));
            Object second=harness.tx().execute(status->submit(harness,command));
            assertThat(id(second)).isEqualTo(id(first));assertThat(count("jobs")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(1);
            jdbc.update("UPDATE jobs SET state='SUCCEEDED' WHERE id=?",id(first));
            Object third=harness.tx().execute(status->submit(harness,command));
            assertThat(id(third)).isEqualTo(id(first));assertThat(count("outbox_events")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,id(first))).isEqualTo("SUCCEEDED");
        }
    }
    @Test void conflictingPayloadCannotOverwriteExistingJob(){
        try(var harness=harness()){
            Object first=harness.tx().execute(status->submit(harness,command(1001,"payload-conflict","BACKGROUND",Map.of("document_version_id",11L))));
            assertThatThrownBy(()->harness.tx().execute(status->submit(harness,command(1001,"payload-conflict","BACKGROUND",Map.of("document_version_id",12L)))))
                .isInstanceOf(BusinessException.class).satisfies(error->assertThat(((BusinessException)error).code()).isEqualTo("job_conflict"));
            assertThat(jdbc.queryForObject("SELECT CAST(JSON_EXTRACT(payload,'$.document_version_id') AS UNSIGNED) FROM jobs WHERE id=?",Long.class,id(first))).isEqualTo(11L);
            assertThat(count("jobs")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(1);
        }
    }
    @Test void enqueueOutsideBusinessTransactionIsRejected(){
        try(var harness=harness()){
            assertThatThrownBy(()->submit(harness,command(1001,"no-transaction","BACKGROUND",Map.of("document_version_id",11L))))
                .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(count("jobs")).isZero();assertThat(count("outbox_events")).isZero();
        }
    }
    @Test void identicalBusinessKeysAreIndependentAcrossTenants(){
        try(var harness=harness()){
            Object first=harness.tx().execute(status->submit(harness,command(1001,"tenant-key","BACKGROUND",Map.of("document_version_id",11L))));
            Object second=harness.tx().execute(status->submit(harness,command(1002,"tenant-key","BACKGROUND",Map.of("document_version_id",11L))));
            assertThat(id(second)).isNotEqualTo(id(first));assertThat(count("jobs")).isEqualTo(2);assertThat(count("outbox_events")).isEqualTo(2);
        }
    }
    @Test void conversationCancellationExcludesBackgroundAndNewerEpoch(){
        try(var harness=harness()){
            Object old=harness.tx().execute(status->submit(harness,command(1001,"foreground-old","INTERACTIVE",Map.of("conversation_id",3001L,"generation_epoch",1L))));
            Object newer=harness.tx().execute(status->submit(harness,command(1001,"foreground-new","INTERACTIVE",Map.of("conversation_id",3001L,"generation_epoch",3L))));
            Object background=harness.tx().execute(status->submit(harness,command(1001,"background","BACKGROUND",Map.of("conversation_id",3001L,"generation_epoch",1L))));
            Object changed=harness.tx().execute(status->invoke(harness.journal(),"cancelForConversation",new Class<?>[]{long.class,long.class,long.class},1001L,3001L,3L));
            assertThat(((Number)changed).intValue()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,id(old))).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,id(newer))).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,id(background))).isEqualTo("QUEUED");
        }
    }
}
