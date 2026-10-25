package cn.servicehub.auth;

import cn.servicehub.common.Actor;
import java.util.HashMap;
import java.util.stream.LongStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationService {
    private final MemberMapper members;
    public AuthorizationService(MemberMapper members){this.members=members;}
    /** The authorization lock is held by the caller's business transaction through commit. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireForWrite(Actor actor){requireCurrent(actor,members.lockById(actor.tenantId(),actor.userId()));}
    @Transactional(propagation=Propagation.MANDATORY)
    public MemberMapper.UserRow lockActorAndTarget(Actor actor,long targetId){
        var locked=new HashMap<Long,MemberMapper.UserRow>();
        LongStream.of(actor.userId(),targetId).distinct().sorted().forEach(id->locked.put(id,members.lockById(actor.tenantId(),id)));
        requireCurrent(actor,locked.get(actor.userId()));return locked.get(targetId);
    }
    public record ExecutionAuthorization(Actor actor,boolean permitted) { }
    /** Allows the runtime to record cancellation without reusing a stale positive authorization. */
    @Transactional(propagation=Propagation.MANDATORY)
    public ExecutionAuthorization inspectForExecution(long tenantId,long userId,long expectedVersion){
        var row=members.lockById(tenantId,userId);
        return new ExecutionAuthorization(row==null?null:row.actor(),row!=null&&row.active()&&row.sessionVersion()==expectedVersion);
    }
    public void requireCurrent(Actor actor){requireCurrent(actor,members.byId(actor.tenantId(),actor.userId()));}
    private void requireCurrent(Actor actor,MemberMapper.UserRow row){
        if(row==null||!row.active()||row.sessionVersion()!=actor.authVersion()||!row.role().equals(actor.role().wireValue()))
            throw SessionStore.unauthorized("账号权限已变更，请重新登录");
    }
}
