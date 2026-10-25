package cn.servicehub.aftersales;

import cn.servicehub.auth.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/after-sales")
public class AfterSaleController {
    private final AfterSaleService sales;
    public AfterSaleController(AfterSaleService sales){this.sales=sales;}
    @PostMapping("/drafts") ResponseEntity<AfterSaleDtos.DraftDto> prepare(@Valid @RequestBody AfterSaleDtos.DraftInput input,HttpServletRequest request){
        return ResponseEntity.status(201).body(sales.prepare(AuthController.actor(request),input.orderId(),input.kind().name(),input.reason()));
    }
    @GetMapping("/drafts") AfterSaleDtos.DraftPage drafts(HttpServletRequest request){return sales.drafts(AuthController.actor(request));}
    @PostMapping("/drafts/{draft_id}/confirm") AfterSaleDtos.TicketDto confirm(@PathVariable("draft_id") long id,@Valid @RequestBody AfterSaleDtos.ConfirmInput input,HttpServletRequest request){
        return sales.confirm(AuthController.actor(request),id,input.version(),input.idempotencyKey());
    }
    @GetMapping AfterSaleDtos.TicketPage list(HttpServletRequest request,@RequestParam(required=false) @Min(1) Long cursor,@RequestParam(defaultValue="30") @Min(1) @Max(100) int limit,@RequestParam(required=false) String status){return sales.list(AuthController.actor(request),cursor,limit,status);}
    @GetMapping("/{ticket_id}") AfterSaleDtos.TicketDto detail(@PathVariable("ticket_id") long id,HttpServletRequest request){return sales.get(AuthController.actor(request),id);}
    @PatchMapping("/{ticket_id}/status") AfterSaleDtos.TicketDto transition(@PathVariable("ticket_id") long id,@Valid @RequestBody AfterSaleDtos.TransitionInput input,HttpServletRequest request){
        return sales.transition(AuthController.actor(request),id,input.version,input.status.name(),input.note);
    }
}
