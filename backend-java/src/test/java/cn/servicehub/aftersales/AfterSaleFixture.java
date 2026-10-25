package cn.servicehub.aftersales;

import cn.servicehub.support.HttpIntegrationApp;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

final class AfterSaleFixture implements AutoCloseable {
    final HttpIntegrationApp app=new HttpIntegrationApp();
    HttpIntegrationApp.Login customer,agent,admin,other;
    void start() throws Exception {
        app.start();String hash=app.db.queryForObject("SELECT password_hash FROM users WHERE id=2001",String.class);
        app.db.update("INSERT INTO users(id,tenant_id,email,display_name,password_hash,role) VALUES(2005,1001,'user2005@example.com','Other customer',?,'customer')",hash);
        app.db.update("INSERT INTO orders(id,tenant_id,customer_id,order_number,total_amount) VALUES(6001,1001,2003,'ORD-A-001',12.30),(6002,1001,2005,'ORD-B-001',99.00),(6003,1002,5001,'ORD-C-001',77.00)");
    }
    void reset() throws Exception {
        app.db.update("DELETE FROM ticket_events");app.db.update("DELETE FROM after_sale_tickets");app.db.update("DELETE FROM ticket_drafts");app.db.update("DELETE FROM audit_logs");
        app.db.update("UPDATE orders SET status='DELIVERED'");app.resetAuth();
        customer=app.login(2003);agent=app.login(2002);admin=app.login(2001);other=app.login(2005);
    }
    long draft(long orderId) throws Exception {
        var r=app.request("POST","/api/v1/after-sales/drafts",Map.of("order_id",orderId,"kind","RETURN","reason","商品需要退货"),customer);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return app.body(r).path("id").asLong();
    }
    java.net.http.HttpResponse<String> confirm(long draft,String key) throws Exception {
        return app.request("POST","/api/v1/after-sales/drafts/"+draft+"/confirm",Map.of("version",1,"idempotency_key",key),customer);
    }
    @Override public void close(){app.close();}
}
