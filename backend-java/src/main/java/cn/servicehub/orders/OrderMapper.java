package cn.servicehub.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {
    record OrderRow(long id,String orderNumber,long customerId,String status,BigDecimal totalAmount,String currency,
        String trackingNumber,String carrier,String deliverySummary,Instant createdAt){
        public OrderDtos.OrderDto dto(List<OrderDtos.OrderItemDto> items){return new OrderDtos.OrderDto(id,orderNumber,customerId,status,totalAmount,currency,trackingNumber,carrier,deliverySummary,createdAt,items);}
    }
    OrderRow lockById(@Param("tenantId") long tenantId,@Param("id") long id,@Param("customerId") Long customerId);
    OrderRow byNumber(@Param("tenantId") long tenantId,@Param("number") String number,@Param("customerId") Long customerId);
    OrderRow lockByNumber(@Param("tenantId") long tenantId,@Param("number") String number,@Param("customerId") Long customerId);
    List<OrderRow> list(@Param("tenantId") long tenantId,@Param("customerId") Long customerId,@Param("cursor") Long cursor,@Param("limit") int limit,@Param("search") String search);
    List<OrderDtos.OrderItemDto> items(@Param("tenantId") long tenantId,@Param("id") long id);
}
