package cn.servicehub.auth;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class AuthDtos {
    private AuthDtos() { }
    public record LoginInput(@Positive long tenantId,@NotBlank @Email String email,@NotNull @Size(min=1,max=128) String password) {
        @Override public String toString(){return "LoginInput[REDACTED]";}
    }
    public static final class MemberInput {
        @NotBlank @Email public String email;
        @NotNull @Size(min=1,max=80) public String displayName;
        @NotNull public Role role=Role.AGENT;
        @NotNull @Size(min=12,max=128) public String password;
        public String email(){return email;}
        public String displayName(){return displayName;}
        public Role role(){return role;}
        public String password(){return password;}
        @Override public String toString(){return "MemberInput[REDACTED]";}
    }
    public record MemberUpdate(Role role,Boolean isActive,@Size(min=1,max=80) String displayName) { }
    public record UserDto(long id,long tenantId,String email,String displayName,Role role,boolean isActive,Instant createdAt) { }
    public record SessionDto(UserDto user,String csrfToken) { }
    public record MemberPage(List<UserDto> items,Long nextCursor) { }
}
