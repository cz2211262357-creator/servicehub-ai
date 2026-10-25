package cn.servicehub.conversations;

import cn.servicehub.auth.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService conversations;private final MessageRateLimiter rate;
    public ConversationController(ConversationService conversations,MessageRateLimiter rate){this.conversations=conversations;this.rate=rate;}
    @PostMapping ResponseEntity<ConversationDtos.ConversationDto> create(@Valid @RequestBody ConversationDtos.ConversationInput input,HttpServletRequest request){return ResponseEntity.status(201).body(conversations.create(AuthController.actor(request),input.title));}
    @GetMapping ConversationDtos.ConversationPage list(HttpServletRequest request,@RequestParam(required=false) @Min(1) Long cursor,@RequestParam(defaultValue="30") @Min(1) @Max(100) int limit,@RequestParam(required=false) ConversationDtos.Status status,@RequestParam(required=false) @Size(max=100) String search){return conversations.list(AuthController.actor(request),cursor,limit,status==null?null:status.name(),search);}
    @GetMapping("/{conversation_id}") ConversationDtos.ConversationDto detail(@PathVariable("conversation_id") long id,HttpServletRequest request){return conversations.get(AuthController.actor(request),id);}
    @GetMapping("/{conversation_id}/messages") ConversationDtos.MessagePage messages(@PathVariable("conversation_id") long id,HttpServletRequest request,@RequestParam(required=false) @Min(1) Long cursor,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return conversations.messages(AuthController.actor(request),id,cursor,limit);}
    @PostMapping("/{conversation_id}/messages") ConversationDtos.MessageDto send(@PathVariable("conversation_id") long id,@Valid @RequestBody ConversationDtos.MessageInput input,HttpServletRequest request){var actor=AuthController.actor(request);rate.check(actor);return conversations.send(actor,id,input.clientMessageId(),input.body());}
    @GetMapping("/{conversation_id}/events") ConversationDtos.EventPage events(@PathVariable("conversation_id") long id,HttpServletRequest request,@RequestParam(name="after_seq",defaultValue="0") @Min(0) long afterSeq,@RequestParam(defaultValue="200") @Min(1) @Max(500) int limit){return conversations.events(AuthController.actor(request),id,afterSeq,limit);}
    @PostMapping("/{conversation_id}/handoff") ConversationDtos.ConversationDto handoff(@PathVariable("conversation_id") long id,HttpServletRequest request){return conversations.handoff(AuthController.actor(request),id);}
    @PostMapping("/{conversation_id}/claim") ConversationDtos.ConversationDto claim(@PathVariable("conversation_id") long id,@Valid @RequestBody ConversationDtos.ClaimInput input,HttpServletRequest request){return conversations.claim(AuthController.actor(request),id,input.version());}
    @PostMapping("/{conversation_id}/close") ConversationDtos.ConversationDto close(@PathVariable("conversation_id") long id,HttpServletRequest request){return conversations.close(AuthController.actor(request),id);}
    @GetMapping("/{conversation_id}/context") ConversationDtos.ContextDto context(@PathVariable("conversation_id") long id,HttpServletRequest request){return conversations.context(AuthController.actor(request),id);}
}
