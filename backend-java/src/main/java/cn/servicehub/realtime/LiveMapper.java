package cn.servicehub.realtime;
import org.apache.ibatis.annotations.*;
@Mapper
public interface LiveMapper {
 @Select("SELECT tenant_id FROM conversations WHERE id=#{id}") Long tenant(@Param("id")long conversation);
}
