package cn.servicehub.jobs;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface OutboxMapper {
    record Row(long id,long tenantId,Long jobId,String kind,String payloadText,String owner,long leaseVersion){}
    Row eligible(@Param("id")long id);
    Long initial(@Param("jobId")long jobId);
    List<Long> pending();
    int claim(@Param("id")long id,@Param("owner")String owner);
    Row owned(@Param("id")long id);
    int confirm(@Param("lease")Row lease);
    int release(@Param("lease")Row lease);
}
