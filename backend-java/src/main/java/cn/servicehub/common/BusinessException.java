package cn.servicehub.common;

import java.util.Map;

public final class BusinessException extends RuntimeException {
    private final String code;
    private final int status;
    private final Map<String, Object> details;
    public BusinessException(String code, String message, int status, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }
    public BusinessException(String code, String message, int status) { this(code, message, status, null); }
    public String code() { return code; }
    public int status() { return status; }
    public Map<String, Object> details() { return details; }
}
