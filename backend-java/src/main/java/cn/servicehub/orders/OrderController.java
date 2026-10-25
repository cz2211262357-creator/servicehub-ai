package cn.servicehub.orders;

import cn.servicehub.auth.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orders;
    public OrderController(OrderService orders){this.orders=orders;}
    @GetMapping OrderDtos.OrderPage list(HttpServletRequest request,@RequestParam(required=false) @Min(1) Long cursor,
        @RequestParam(defaultValue="30") @Min(1) @Max(100) int limit,@RequestParam(name="customer_id",required=false) @Min(1) Long customerId,
        @RequestParam(required=false) @Size(max=40) String search){return orders.list(AuthController.actor(request),cursor,limit,customerId,search);}
    @GetMapping("/{order_number}") OrderDtos.OrderDto detail(@PathVariable("order_number") String orderNumber,HttpServletRequest request){return orders.get(AuthController.actor(request),orderNumber);}
}
