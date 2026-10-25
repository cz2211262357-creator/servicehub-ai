package cn.servicehub.common;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(Instant.class)
@MappedJdbcTypes(value=JdbcType.TIMESTAMP, includeNullJdbcType=true)
public final class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
    @Override public void setNonNullParameter(PreparedStatement statement,int index,Instant value,JdbcType type) throws SQLException {
        statement.setObject(index,LocalDateTime.ofInstant(value.truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC));
    }
    private Instant instant(LocalDateTime value) { return value==null?null:value.toInstant(ZoneOffset.UTC); }
    @Override public Instant getNullableResult(ResultSet result,String name) throws SQLException { return instant(result.getObject(name,LocalDateTime.class)); }
    @Override public Instant getNullableResult(ResultSet result,int index) throws SQLException { return instant(result.getObject(index,LocalDateTime.class)); }
    @Override public Instant getNullableResult(CallableStatement statement,int index) throws SQLException { return instant(statement.getObject(index,LocalDateTime.class)); }
}
