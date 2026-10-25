package cn.servicehub.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {
    private OrderDtos() { }
    public record OrderItemDto(long id,String productName,String sku,int quantity,BigDecimal unitPrice) { }
    public record OrderDto(long id,String orderNumber,long customerId,String status,BigDecimal totalAmount,String currency,
        String trackingNumber,String carrier,String deliverySummary,Instant createdAt,List<OrderItemDto> items) { }
    public record OrderPage(List<OrderDto> items,Long nextCursor) { }
}
