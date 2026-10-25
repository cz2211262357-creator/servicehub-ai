package cn.servicehub.auth;

import cn.servicehub.common.Actor;
import cn.servicehub.common.RequestIdFilter;
import cn.servicehub.config.ServiceHubProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    public static final String AUTH_ATTRIBUTE="servicehub.authentication";
    private final AuthService auth;private final MemberService members;private final SessionStore sessions;private final ServiceHubProperties properties;
    public AuthController(AuthService auth,MemberService members,SessionStore sessions,ServiceHubProperties properties){this.auth=auth;this.members=members;this.sessions=sessions;this.properties=properties;}
    @PostMapping("/auth/login") ResponseEntity<AuthDtos.SessionDto> login(@Valid @RequestBody AuthDtos.LoginInput input,HttpServletRequest request){
        var result=auth.login(input,request.getRemoteAddr());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie(result.token(),properties.sessionTtlSeconds()).toString()).body(result.session());
    }
    @GetMapping("/auth/me") AuthDtos.SessionDto me(HttpServletRequest request){var session=session(request);return new AuthDtos.SessionDto(auth.currentUser(session.actor()),session.csrfToken());}
    @PostMapping("/auth/logout") ResponseEntity<Void> logout(HttpServletRequest request){sessions.revokeDigest(session(request).sessionDigest());return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,cookie("",0).toString()).build();}
    @GetMapping("/admin/members") AuthDtos.MemberPage members(HttpServletRequest request,@RequestParam(required=false) @Min(1) Long cursor,@RequestParam(defaultValue="30") @Min(1) @Max(100) int limit){return members.list(actor(request),cursor,limit);}
    @PostMapping("/admin/members") ResponseEntity<AuthDtos.UserDto> create(@Valid @RequestBody AuthDtos.MemberInput input,HttpServletRequest request){
        // Hash before the authorization/business transaction takes row locks.
        var actor=actor(request);if(actor.role()!=Role.ADMIN)throw new cn.servicehub.common.BusinessException("forbidden","当前账号无权执行此操作",403);
        return ResponseEntity.status(201).body(members.create(actor,input,auth.hashPassword(input.password()),requestId(request)));
    }
    @PatchMapping("/admin/members/{member_id}") AuthDtos.UserDto update(@PathVariable("member_id") long memberId,@Valid @RequestBody AuthDtos.MemberUpdate input,HttpServletRequest request){return members.update(actor(request),memberId,input,requestId(request));}
    public static SessionStore.SessionDto session(HttpServletRequest request){return (SessionStore.SessionDto)request.getAttribute(AUTH_ATTRIBUTE);}
    public static Actor actor(HttpServletRequest request){return session(request).actor();}
    private String requestId(HttpServletRequest request){return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));}
    private ResponseCookie cookie(String value,int maxAge){return ResponseCookie.from(properties.sessionCookieName(),value).path("/").httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax").maxAge(maxAge).build();}
}
