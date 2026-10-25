package cn.servicehub.auth;

import cn.servicehub.common.Actor;
import cn.servicehub.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MemberService {
    private final cn.servicehub.operations.AuditService audit;private final MemberMapper members;private final AuthorizationService authorization;private final SessionStore sessions;private final JsonMapper json;
    public MemberService(MemberMapper members,AuthorizationService authorization,SessionStore sessions,JsonMapper json,cn.servicehub.operations.AuditService audit){this.audit=audit;this.members=members;this.authorization=authorization;this.sessions=sessions;this.json=json;}
    public AuthDtos.MemberPage list(Actor actor,Long cursor,int limit){
        admin(actor);authorization.requireCurrent(actor);var rows=members.list(actor.tenantId(),cursor,limit+1);
        return new AuthDtos.MemberPage(rows.stream().limit(limit).map(MemberMapper.UserRow::dto).toList(),rows.size()>limit?rows.get(limit-1).id():null);
    }
    @Transactional
    public AuthDtos.UserDto create(Actor actor,AuthDtos.MemberInput input,String hash,String requestId){
        admin(actor);authorization.requireForWrite(actor);
        var row=new LinkedHashMap<String,Object>();row.put("tenantId",actor.tenantId());row.put("email",input.email().toLowerCase(Locale.ROOT));
        row.put("displayName",input.displayName());row.put("passwordHash",hash);row.put("role",input.role().wireValue());
        try{members.insert(row);}catch(DuplicateKeyException e){throw new BusinessException("member_exists","该账号已存在",409);}
        long id=((Number)row.get("id")).longValue();audit.append(actor,"member.created","user",id,"success",requestId,java.util.Map.of("role",input.role().wireValue()));
        return members.byId(actor.tenantId(),id).dto();
    }
    @Transactional
    public AuthDtos.UserDto update(Actor actor,long id,AuthDtos.MemberUpdate input,String requestId){
        admin(actor);var target=authorization.lockActorAndTarget(actor,id);
        if(target==null)throw new BusinessException("not_found","成员不存在",404);
        if(id==actor.userId()&&(input.role()!=null&&input.role()!=Role.ADMIN||Boolean.FALSE.equals(input.isActive())))
            throw new BusinessException("self_admin_change","不能停用或降级当前管理员账号",409);
        boolean bump=input.role()!=null||input.isActive()!=null;
        members.update(actor.tenantId(),id,input.role()==null?null:input.role().wireValue(),input.isActive(),input.displayName(),bump);
        var changed=new LinkedHashMap<String,Object>();if(input.role()!=null)changed.put("role",input.role().wireValue());
        if(input.isActive()!=null)changed.put("is_active",input.isActive());if(input.displayName()!=null)changed.put("display_name",input.displayName());
        audit.append(actor,"member.updated","user",id,"success",requestId,changed);
        if(bump)TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){
                try{sessions.revokeUser(actor.tenantId(),id);}catch(BusinessException e){LoggerFactory.getLogger(MemberService.class).warn("session_cache_revocation_deferred code={}",e.code());}
            }
        });
        return members.byId(actor.tenantId(),id).dto();
    }
    private void admin(Actor actor){if(actor.role()!=Role.ADMIN)throw new BusinessException("forbidden","当前账号无权执行此操作",403);}
}
