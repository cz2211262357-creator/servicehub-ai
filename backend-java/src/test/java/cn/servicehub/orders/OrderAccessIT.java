package cn.servicehub.orders;

import cn.servicehub.support.HttpIntegrationApp;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderAccessIT {
    final HttpIntegrationApp app=new HttpIntegrationApp();
    @BeforeAll void start() throws Exception {
        app.start();String hash=app.db.queryForObject("SELECT password_hash FROM users WHERE id=2001",String.class);
        app.db.update("INSERT INTO users(id,tenant_id,email,display_name,password_hash,role) VALUES(2005,1001,'user2005@example.com','Other customer',?,'customer')",hash);
        app.db.update("INSERT INTO orders(id,tenant_id,customer_id,order_number,total_amount) VALUES(6001,1001,2003,'ORD-A-001',12.30),(6002,1001,2005,'ORD-B-001',99.00),(6003,1002,5001,'ORD-C-001',77.00)");
        app.db.update("INSERT INTO order_items(id,tenant_id,order_id,product_name,sku,quantity,unit_price) VALUES(7001,1001,6001,'合成商品','SKU-001',2,6.15)");
    }
    @BeforeEach void reset(){app.resetAuth();}
    @AfterAll void stop(){app.close();}
    @Test void orderNumberIsStringAndAmountsStayDecimalStrings() throws Exception {
        var customer=app.login(2003);var r=app.request("GET","/api/v1/orders/ORD-A-001",null,customer);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var data=app.body(r);assertThat(data.path("order_number").asString()).isEqualTo("ORD-A-001");assertThat(data.path("total_amount").asString()).isEqualTo("12.30");
        assertThat(data.path("total_amount").isString()).isTrue();assertThat(data.at("/items/0/unit_price").asString()).isEqualTo("6.15");
        assertThat(data.path("tracking_number").isNull()).isTrue();assertThat(data.path("created_at").asString()).endsWith("Z");
        assertThat(app.request("GET","/api/v1/orders/6001",null,customer).statusCode()).isEqualTo(404);
    }
    @Test void orderReadsNeverCrossTenantOrCustomerBoundary() throws Exception {
        var customer=app.login(2003);var agent=app.login(2002);
        assertThat(app.request("GET","/api/v1/orders/ORD-B-001",null,customer).statusCode()).isEqualTo(404);
        assertThat(app.request("GET","/api/v1/orders/ORD-C-001",null,agent).statusCode()).isEqualTo(404);
        assertThat(app.request("GET","/api/v1/orders/ORD-B-001",null,agent).statusCode()).isEqualTo(200);
        var own=app.request("GET","/api/v1/orders?customer_id=2005",null,customer);assertThat(own.statusCode()).isEqualTo(200);
        assertThat(app.body(own).path("items").size()).isEqualTo(1);assertThat(app.body(own).at("/items/0/customer_id").asLong()).isEqualTo(2003);
        var otherConversation=app.request("GET","/api/v1/conversations/3001",null,app.login(2005));assertThat(otherConversation.statusCode()).isEqualTo(404);
    }
    @Test void orderListPreservesCursorSearchAndStaffCustomerFilter() throws Exception {
        var admin=app.login(2001);var r=app.request("GET","/api/v1/orders?limit=1",null,admin);assertThat(r.statusCode()).isEqualTo(200);
        assertThat(app.body(r).path("items").size()).isEqualTo(1);assertThat(app.body(r).path("next_cursor").isNull()).isFalse();
        var filtered=app.request("GET","/api/v1/orders?customer_id=2005&search=ORD-B-001",null,admin);assertThat(app.body(filtered).path("items").size()).isEqualTo(1);
        assertThat(app.body(filtered).at("/items/0/items").size()).isZero();
        assertThat(app.request("GET","/api/v1/orders?limit=101",null,admin).statusCode()).isEqualTo(422);
    }
}
