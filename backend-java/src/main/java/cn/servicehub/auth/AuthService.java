package cn.servicehub.auth;

import cn.servicehub.common.Actor;
import cn.servicehub.common.BusinessException;
import java.util.Locale;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final MemberMapper members;private final SessionStore sessions;private final AuthorizationService authorization;
    private final Argon2PasswordEncoder passwords=new Argon2PasswordEncoder(16,32,4,65536,3);
    private final String dummyHash=passwords.encode(SessionStore.newToken());
    public record LoginResult(String token,AuthDtos.SessionDto session){@Override public String toString(){return "LoginResult[REDACTED]";}}
    public AuthService(MemberMapper members,SessionStore sessions,AuthorizationService authorization){this.members=members;this.sessions=sessions;this.authorization=authorization;}
    public LoginResult login(AuthDtos.LoginInput input,String ip){
        String email=input.email().toLowerCase(Locale.ROOT);sessions.checkLoginRate(input.tenantId(),email,ip);
        var user=members.byEmail(input.tenantId(),email);boolean valid;
        try{valid=passwords.matches(input.password(),user==null?dummyHash:user.passwordHash());}catch(IllegalArgumentException e){valid=false;}
        if(user==null||!valid||!user.active())throw new BusinessException("invalid_credentials","企业编号、账号或密码不正确",401);
        var session=sessions.create(user.actor());
        return new LoginResult(session.sessionId(),new AuthDtos.SessionDto(user.dto(),session.csrfToken()));
    }
    public Actor authenticate(String sessionId){return authenticateSession(sessionId).actor();}
    public SessionStore.SessionDto authenticateSession(String token){
        var session=sessions.load(token);authorization.requireCurrent(session.actor());return session;
    }
    public AuthDtos.UserDto currentUser(Actor actor){authorization.requireCurrent(actor);return members.byId(actor.tenantId(),actor.userId()).dto();}
    public void requireConversationAccess(Actor actor,long conversationId){
        authorization.requireCurrent(actor);Long customer=members.conversationCustomer(actor.tenantId(),conversationId);
        if(customer==null||actor.role()==Role.CUSTOMER&&customer!=actor.userId())throw new BusinessException("not_found","会话不存在或无权访问",404);
    }
    public AuthDtos.UserDto customerProfile(Actor actor,long customerId){
        if(actor.role()==Role.CUSTOMER)throw new BusinessException("forbidden","当前账号无权执行此操作",403);
        authorization.requireCurrent(actor);var user=members.byId(actor.tenantId(),customerId);
        if(user==null)throw new BusinessException("not_found","成员不存在",404);
        return user.dto();
    }
    public String hashPassword(String value){return passwords.encode(value);}
}
