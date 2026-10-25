package cn.servicehub.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum Role {
    ADMIN, AGENT, CUSTOMER;
    @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
    @JsonCreator public static Role fromWire(String value) { return switch (value) { case "admin" -> ADMIN; case "agent" -> AGENT; case "customer" -> CUSTOMER; default -> throw new IllegalArgumentException("Unknown role"); }; }
}
