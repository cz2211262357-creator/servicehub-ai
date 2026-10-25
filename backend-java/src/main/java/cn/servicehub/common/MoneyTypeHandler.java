package cn.servicehub.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(BigDecimal.class)
@MappedJdbcTypes(value={JdbcType.DECIMAL,JdbcType.NUMERIC}, includeNullJdbcType=true)
public final class MoneyTypeHandler extends BaseTypeHandler<BigDecimal> {
    private static final BigDecimal MAX = new BigDecimal("9999999999.99");
    public static BigDecimal forStorage(BigDecimal value) {
        if(value==null || value.scale()>2 || value.signum()<0 || value.compareTo(MAX)>0) {
            throw new IllegalArgumentException("Amount must be a nonnegative DECIMAL(12,2) without extra fractional digits");
        }
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }
    @Override public void setNonNullParameter(PreparedStatement statement,int index,BigDecimal value,JdbcType type) throws SQLException {
        statement.setBigDecimal(index,forStorage(value));
    }
    @Override public BigDecimal getNullableResult(ResultSet result,String name) throws SQLException { return result.getBigDecimal(name); }
    @Override public BigDecimal getNullableResult(ResultSet result,int index) throws SQLException { return result.getBigDecimal(index); }
    @Override public BigDecimal getNullableResult(CallableStatement statement,int index) throws SQLException { return statement.getBigDecimal(index); }
}
