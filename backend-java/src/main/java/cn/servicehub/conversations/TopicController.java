package cn.servicehub.conversations;
import cn.servicehub.auth.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/conversations/{conversation_id}")
public class TopicController {
    private final TopicService topics;
    public TopicController(TopicService topics){this.topics=topics;}
    @PostMapping("/interrupt") public ConversationDtos.ConversationDto interrupt(HttpServletRequest request,@PathVariable("conversation_id")long id,@Valid @RequestBody TopicDtos.InterruptInput input){return topics.interrupt(AuthController.actor(request),id,input.expectedGenerationEpoch());}
    @GetMapping("/topics") public TopicDtos.TopicPage list(HttpServletRequest request,@PathVariable("conversation_id")long id){return topics.list(AuthController.actor(request),id);}
    @PostMapping("/topics/switch") public ConversationDtos.ConversationDto change(HttpServletRequest request,@PathVariable("conversation_id")long id,@Valid @RequestBody TopicDtos.TopicSwitchCommand input){return topics.switchTopic(AuthController.actor(request),id,input);}
}
