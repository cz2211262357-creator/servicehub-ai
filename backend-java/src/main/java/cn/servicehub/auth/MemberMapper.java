package cn.servicehub.auth;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MemberMapper {
    record UserRow(long id,long tenantId,String email,String displayName,String passwordHash,String role,boolean active,long sessionVersion,Instant createdAt) {
        public cn.servicehub.common.Actor actor(){return new cn.servicehub.common.Actor(tenantId,id,Role.fromWire(role),sessionVersion);}
        public AuthDtos.UserDto dto(){return new AuthDtos.UserDto(id,tenantId,email,displayName,Role.fromWire(role),active,createdAt);}
    }
    UserRow byEmail(@Param("tenantId") long tenantId,@Param("email") String email);
    UserRow byId(@Param("tenantId") long tenantId,@Param("id") long id);
    UserRow lockById(@Param("tenantId") long tenantId,@Param("id") long id);
    List<UserRow> list(@Param("tenantId") long tenantId,@Param("cursor") Long cursor,@Param("limit") int limit);
    int insert(Map<String,Object> row);
    int update(@Param("tenantId") long tenantId,@Param("id") long id,@Param("role") String role,@Param("active") Boolean active,@Param("displayName") String displayName,@Param("bump") boolean bump);

    Long conversationCustomer(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId);
}
