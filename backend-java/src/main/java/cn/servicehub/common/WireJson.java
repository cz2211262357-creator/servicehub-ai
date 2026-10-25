package cn.servicehub.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

public final class WireJson {
    private WireJson() { }
    private static final DateTimeFormatter SIX_DIGITS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    public static SimpleModule module() {
        var module = new SimpleModule("servicehub-wire");
        module.addSerializer(BigDecimal.class,new ValueSerializer<BigDecimal>() {
            @Override public void serialize(BigDecimal value,JsonGenerator output,SerializationContext context) throws JacksonException {
                output.writeString(value.toPlainString());
            }
        });
        module.addSerializer(Instant.class,new ValueSerializer<Instant>() {
            @Override public void serialize(Instant value,JsonGenerator output,SerializationContext context) throws JacksonException {
                var micros=value.truncatedTo(ChronoUnit.MICROS);
                output.writeString(micros.getNano()==0?micros.toString():SIX_DIGITS.format(micros));
            }
        });
        return module;
    }
}
