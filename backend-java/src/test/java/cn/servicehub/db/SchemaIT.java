package cn.servicehub.db;

import cn.servicehub.support.DependencyContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SchemaIT {
    private static final DependencyContainers dependencies = new DependencyContainers();
    private static Flyway flyway;
    @BeforeAll static void start() {
        dependencies.start();
        flyway = Flyway.configure().dataSource(dependencies.jdbcUrl(), dependencies.username(), dependencies.password())
            .locations("classpath:db/migration").load();
        flyway.migrate();
    }
    @AfterAll static void stop() { dependencies.close(); }
    @Test void migrationCreatesTenantBusinessAndControlTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(dependencies.jdbcUrl(), dependencies.username(), dependencies.password());
             var tables = connection.getMetaData().getTables("servicehub_java_it", null, "%", new String[]{"TABLE"})) {
            var names = new HashSet<String>();
            while (tables.next()) names.add(tables.getString("TABLE_NAME"));
            assertThat(names).contains("tenants", "users", "conversations", "messages", "conversation_events", "orders", "order_items",
                "ticket_drafts", "after_sale_tickets", "ticket_events", "knowledge_documents", "document_versions", "knowledge_chunks",
                "knowledge_active_versions", "knowledge_index_generations", "ai_runs", "tool_calls", "jobs", "outbox_events", "audit_logs", "conversation_topics", "conversation_interruptions");
        }
    }
    @Test void runningMigrationAgainDoesNotRecreateOrDuplicateSchema() {
        assertThat(flyway.info().applied()).hasSize(6);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private Connection fixture() throws Exception {
        assertThat(flyway.info().applied()).hasSize(6);
        var connection = DriverManager.getConnection(dependencies.jdbcUrl(), dependencies.username(), dependencies.password());
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO tenants(id,name) VALUES(1001,'schema-a'),(1002,'schema-b')");
            statement.executeUpdate("INSERT INTO users(id,tenant_id,email,display_name,password_hash,role) VALUES(2001,1001,'a@example.test','A','synthetic','customer'),(2002,1002,'b@example.test','B','synthetic','customer')");
            statement.executeUpdate("INSERT INTO conversations(id,tenant_id,customer_id) VALUES(3001,1001,2001),(3002,1001,2001)");
            statement.executeUpdate("INSERT INTO conversation_topics(id,tenant_id,conversation_id,title) VALUES(4001,1001,3001,'A'),(4002,1001,3001,'B'),(4003,1001,3002,'C')");
        }
        return connection;
    }
    @Test void foreignTenantCustomerCannotOwnConversation() throws Exception {
        try (var connection = fixture()) {
            try (var statement = connection.createStatement()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO conversations(tenant_id,customer_id) VALUES(1001,2002)"))
                    .isInstanceOf(java.sql.SQLException.class).satisfies(error -> assertThat(((java.sql.SQLException)error).getErrorCode()).isEqualTo(1452));
            } finally { connection.rollback(); }
        }
    }
    @Test void sameClientMessageIdCannotBeReusedAcrossTopicsOfOneConversation() throws Exception {
        try (var connection = fixture()) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO messages(tenant_id,conversation_id,sender_id,author_role,client_message_id,body,topic_id,routing_state) VALUES(1001,3001,2001,'customer','same-client-id','hello',4001,'RESOLVED')");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO messages(tenant_id,conversation_id,sender_id,author_role,client_message_id,body,topic_id,routing_state) VALUES(1001,3001,2001,'customer','same-client-id','other',4002,'RESOLVED')"))
                    .isInstanceOf(java.sql.SQLException.class).satisfies(error -> assertThat(((java.sql.SQLException)error).getErrorCode()).isEqualTo(1062));
            } finally { connection.rollback(); }
        }
    }
    @Test void topicFromAnotherConversationCannotBeAttachedToMessage() throws Exception {
        try (var connection = fixture()) {
            try (var statement = connection.createStatement()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO messages(tenant_id,conversation_id,sender_id,author_role,client_message_id,body,topic_id,routing_state) VALUES(1001,3001,2001,'customer','wrong-topic','hello',4003,'RESOLVED')"))
                    .isInstanceOf(java.sql.SQLException.class).satisfies(error -> assertThat(((java.sql.SQLException)error).getErrorCode()).isEqualTo(1452));
            } finally { connection.rollback(); }
        }
    }
}
