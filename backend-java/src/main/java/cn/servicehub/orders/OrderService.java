package cn.servicehub.orders;

import cn.servicehub.auth.AuthorizationService;
import cn.servicehub.auth.Role;
import cn.servicehub.common.Actor;
import cn.servicehub.common.BusinessException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderMapper orders;private final AuthorizationService authorization;
    public OrderService(OrderMapper orders,AuthorizationService authorization){this.orders=orders;this.authorization=authorization;}
    public OrderDtos.OrderDto get(Actor actor,String orderNumber){authorization.requireCurrent(actor);return detail(actor,orders.byNumber(actor.tenantId(),orderNumber,customer(actor,null)));}
    @Transactional(propagation=Propagation.MANDATORY) public OrderDtos.OrderDto lockByNumber(Actor actor,String orderNumber){authorization.requireForWrite(actor);return detail(actor,orders.lockByNumber(actor.tenantId(),orderNumber,customer(actor,null)));}
    @Transactional(propagation=Propagation.MANDATORY) public OrderDtos.OrderDto lockById(Actor actor,long id){authorization.requireForWrite(actor);return detail(actor,orders.lockById(actor.tenantId(),id,customer(actor,null)));}
    public OrderDtos.OrderPage list(Actor actor,Long cursor,int limit,Long customerId,String search){
        authorization.requireCurrent(actor);var rows=orders.list(actor.tenantId(),customer(actor,customerId),cursor,limit+1,search);
        return new OrderDtos.OrderPage(rows.stream().limit(limit).map(r->r.dto(List.of())).toList(),rows.size()>limit?rows.get(limit-1).id():null);
    }
    public List<OrderDtos.OrderDto> listForCustomer(Actor actor,long customerId,int limit){return list(actor,null,limit,customerId,null).items();}
    private Long customer(Actor actor,Long requested){if(actor.role()==Role.CUSTOMER)return actor.userId();return requested;}
    private OrderDtos.OrderDto detail(Actor actor,OrderMapper.OrderRow row){if(row==null)throw new BusinessException("not_found","订单不存在或无权访问",404);return row.dto(orders.items(actor.tenantId(),row.id()));}
}
