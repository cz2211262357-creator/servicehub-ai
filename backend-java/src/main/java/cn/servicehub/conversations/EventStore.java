package cn.servicehub.conversations;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EventStore {
    private final ConversationMapper conversations;private final JsonMapper json;
    public EventStore(ConversationMapper conversations,JsonMapper json){this.conversations=conversations;this.json=json;}
    @Transactional(propagation=Propagation.MANDATORY)
    public long append(long tenantId,long conversationId,String kind,Map<String,Object> payload){
        if(conversations.nextSeq(tenantId,conversationId)!=1)throw new IllegalStateException("Conversation event owner is absent");
        var row=conversations.get(tenantId,conversationId);var values=new LinkedHashMap<String,Object>();
        values.put("tenantId",tenantId);values.put("conversationId",conversationId);values.put("seq",row.lastEventSeq());
        values.put("kind",kind);values.put("epoch",row.generationEpoch());values.put("payload",encode(tenantId,conversationId,payload));
        conversations.insertEvent(values);conversations.insertNotification(tenantId,conversationId,row.lastEventSeq());return row.lastEventSeq();
    }
    /** Completes references of an event created in this same, still-uncommitted transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    void completeMessageEvent(long tenantId,long conversationId,long seq,ConversationDtos.MessageDto message){
        if(conversations.refreshMessageEvent(tenantId,conversationId,seq,encode(tenantId,conversationId,Map.of("message",message)))!=1)
            throw new IllegalStateException("Message event cannot be finalized");
    }
    private String encode(long tenantId,long conversationId,Map<String,Object> payload){
        var data=new LinkedHashMap<String,Object>(payload);data.put("conversation",snapshot(conversations.get(tenantId,conversationId)));return json.writeValueAsString(data);
    }
    public ConversationDtos.ConversationDto snapshot(ConversationMapper.ConversationRow row){
        ConversationDtos.AIReplyDto reply=null;
        if("AI_ACTIVE".equals(row.status())){
            var run=conversations.reply(row.tenantId(),row.id(),row.generationEpoch());
            if(run!=null){
                String state=java.util.Set.of("CANCELLED","SUPERSEDED").contains(run.runState().toUpperCase(java.util.Locale.ROOT))?"cancelled":wireState(run.jobState());
                reply=new ConversationDtos.AIReplyDto(run.runId(),state,run.errorCode(),"retrying".equals(state),run.generationEpoch(),run.attempts(),run.executionVersion());
            }
        }
        return new ConversationDtos.ConversationDto(row.id(),row.customerId(),row.assignedAgentId(),row.title(),row.status(),row.version(),row.generationEpoch(),row.lastEventSeq(),row.createdAt(),row.updatedAt(),reply,row.activeTopicId());
    }
    private String wireState(String state){return switch(state.toUpperCase(java.util.Locale.ROOT)){case "RETRY_WAIT"->"retrying";case "SUPERSEDED"->"cancelled";case "TIMED_OUT","EXPIRED"->"failed";default->state.toLowerCase(java.util.Locale.ROOT);};}
}
