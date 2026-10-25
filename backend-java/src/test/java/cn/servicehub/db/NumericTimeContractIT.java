package cn.servicehub.db;

import cn.servicehub.support.DependencyContainers;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.TimeZone;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class NumericTimeContractIT {
    private static final DependencyContainers dependencies = new DependencyContainers();
    @BeforeAll static void start() { dependencies.start(); }
    @AfterAll static void stop() { dependencies.close(); }
    @SuppressWarnings("unchecked")
    private <T> TypeHandler<T> handler(String name) {
        return assertDoesNotThrow(() -> (TypeHandler<T>) Class.forName("cn.servicehub.common."+name).getConstructor().newInstance());
    }
    private Connection connection() throws Exception {
        return DriverManager.getConnection(dependencies.jdbcUrl(), dependencies.username(), dependencies.password());
    }
    @ParameterizedTest @ValueSource(strings={"UTC","Asia/Shanghai","America/New_York"})
    void sameInstantRoundTripsAsUtcMicrosecondsRegardlessOfJvmTimezone(String zone) throws Exception {
        var previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try (var connection = connection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMPORARY TABLE codec_time(at_value DATETIME(6) NOT NULL)");
                try (var timezone = statement.executeQuery("SELECT @@session.time_zone")) {
                    assertThat(timezone.next()).isTrue();
                    assertThat(timezone.getString(1)).isEqualTo("+00:00");
                }
            }
            TypeHandler<Instant> handler = handler("UtcInstantTypeHandler");
            String[][] cases = {{"2026-10-22T00:00:00.123456789Z","2026-10-22T00:00:00.123456Z"},
                {"2026-11-01T05:30:00.123000Z","2026-11-01T05:30:00.123000Z"},
                {"2026-03-08T07:00:00Z","2026-03-08T07:00:00Z"}};
            for (var fixture : cases) {
                try (var statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM codec_time"); }
                try (var insert = connection.prepareStatement("INSERT INTO codec_time(at_value) VALUES(?)")) {
                    handler.setParameter(insert,1,Instant.parse(fixture[0]),JdbcType.TIMESTAMP);
                    insert.executeUpdate();
                }
                try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT at_value FROM codec_time")) {
                    assertThat(row.next()).isTrue();
                    assertThat(handler.getResult(row,"at_value")).isEqualTo(Instant.parse(fixture[1]));
                }
            }
        } finally { TimeZone.setDefault(previous); }
    }
    @ParameterizedTest @ValueSource(strings={"12.345","10000000000.00","-0.01"})
    void monetaryWriteRejectsInvalidScaleRangeAndNegativeValues(String input) throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE codec_money(amount DECIMAL(12,2) NOT NULL)");
            TypeHandler<BigDecimal> handler = handler("MoneyTypeHandler");
            try (var insert = connection.prepareStatement("INSERT INTO codec_money(amount) VALUES(?)")) {
                assertThatThrownBy(() -> { handler.setParameter(insert,1,new BigDecimal(input),JdbcType.DECIMAL); insert.executeUpdate(); })
                    .isInstanceOf(RuntimeException.class);
            }
            try (var row = statement.executeQuery("SELECT COUNT(*) FROM codec_money")) {
                row.next(); assertThat(row.getInt(1)).isZero();
            }
        }
    }
    @Test void validMoneyPreservesTwoDecimalPlacesInStorage() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE codec_valid_money(amount DECIMAL(12,2) NOT NULL)");
            TypeHandler<BigDecimal> handler = handler("MoneyTypeHandler");
            try (var insert = connection.prepareStatement("INSERT INTO codec_valid_money(amount) VALUES(?)")) {
                handler.setParameter(insert,1,new BigDecimal("12.30"),JdbcType.DECIMAL); insert.executeUpdate();
            }
            try (var row = statement.executeQuery("SELECT amount FROM codec_valid_money")) {
                row.next(); assertThat(handler.getResult(row,"amount").toPlainString()).isEqualTo("12.30");
            }
        }
    }
    record WireFixture(Instant created_at, BigDecimal total_amount) { }
    @Test void jsonUsesPythonCompatibleUtcAndPlainDecimalStrings() {
        SimpleModule module = assertDoesNotThrow(() -> (SimpleModule)Class.forName("cn.servicehub.common.WireJson").getMethod("module").invoke(null));
        var mapper = JsonMapper.builder().addModule(module).build();
        String[][] cases={{"2026-10-22T00:00:00Z","2026-10-22T00:00:00Z"},
            {"2026-10-22T00:00:00.123000Z","2026-10-22T00:00:00.123000Z"},
            {"2026-10-22T00:00:00.123456789Z","2026-10-22T00:00:00.123456Z"}};
        for(var fixture:cases){
            String json=mapper.writeValueAsString(new WireFixture(Instant.parse(fixture[0]),new BigDecimal("12.30")));
            assertThat(JsonPath.<Object>read(json,"$.created_at")).isEqualTo(fixture[1]);
            assertThat(JsonPath.<Object>read(json,"$.total_amount")).isEqualTo("12.30");
        }
        String large=mapper.writeValueAsString(new WireFixture(Instant.EPOCH,new BigDecimal("1E+9")));
        assertThat(JsonPath.<Object>read(large,"$.total_amount")).isEqualTo("1000000000");
    }
}
