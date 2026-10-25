package cn.servicehub.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorAdvice {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorAdvice.class);
    private final tools.jackson.databind.json.JsonMapper json;
    public ApiErrorAdvice(tools.jackson.databind.json.JsonMapper json){this.json=json;}
    public record Envelope(ErrorBody error) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, String request_id, Object details) { }

    private ResponseEntity<Envelope> error(HttpServletRequest request, int status, String code, String message, Object details) {
        return ResponseEntity.status(status).body(new Envelope(new ErrorBody(code, message,
            String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)), details)));
    }
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Envelope> business(HttpServletRequest request, BusinessException exception) {
        return error(request, exception.status(), exception.code(), exception.getMessage(), exception.details());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Envelope> validation(HttpServletRequest request, MethodArgumentNotValidException exception) {
        List<Map<String, String>> fields = exception.getBindingResult().getFieldErrors().stream()
            .map(field -> Map.of("field", "body." + snake(field.getField()), "type", bodyType(request,exception.getBindingResult(),field)))
            .toList();
        return error(request, 422, "validation_error", "Request validation failed", fields);
    }
    private static String snake(String value) {return value.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(java.util.Locale.ROOT);}
    private String bodyType(HttpServletRequest request,org.springframework.validation.BindingResult result,org.springframework.validation.FieldError field) {
        var body=originalBody(request);String key=snake(field.getField());
        if(body!=null&&body.isObject()){
            if(!body.has(key))return "missing";
            if(body.get(key).isNull()){
                Class<?> type=result.getFieldType(field.getField());
                if(type==null&&result.getTarget()!=null)try{type=result.getTarget().getClass().getDeclaredField(field.getField()).getType();}catch(NoSuchFieldException ignored){}
                return valueType(type,false);
            }
        }
        String code=field.getCode()==null?"invalid":field.getCode();
        return switch(code) {
            case "Min","DecimalMin" -> "greater_than_equal";
            case "Max","DecimalMax" -> "less_than_equal";
            case "Positive" -> "greater_than";
            case "Pattern" -> "string_pattern_mismatch";
            case "Email" -> "value_error";
            case "NotNull","NotBlank" -> "missing";
            case "Size" -> {
                int minimum=0;
                try {var violation=field.unwrap(jakarta.validation.ConstraintViolation.class);minimum=((Number)violation.getConstraintDescriptor().getAttributes().get("min")).intValue();}
                catch(IllegalArgumentException ignored) {minimum=1;}
                Object rejected=field.getRejectedValue();boolean shortValue=rejected instanceof CharSequence text&&text.length()<minimum;
                yield ("password".equals(field.getField())?"":"string_")+(shortValue?"too_short":"too_long");
            }
            default -> "invalid";
        };
    }
    private byte[] bodyBytes(HttpServletRequest request){
        var cached=org.springframework.web.util.WebUtils.getNativeRequest(request,org.springframework.web.util.ContentCachingRequestWrapper.class);
        return cached==null?new byte[0]:cached.getContentAsByteArray();
    }
    private tools.jackson.databind.JsonNode originalBody(HttpServletRequest request){
        byte[] bytes=bodyBytes(request);if(bytes.length==0)return null;
        try{return json.readTree(bytes);}catch(tools.jackson.core.JacksonException ignored){return null;}
    }
    private static String valueType(Class<?> type,boolean parsing){
        if(type!=null&&type.isEnum())return "literal_error";
        if(type==Boolean.class||type==boolean.class)return parsing?"bool_parsing":"bool_type";
        if(type==String.class||type!=null&&CharSequence.class.isAssignableFrom(type))return "string_type";
        if(type==int.class||type==long.class||type==Integer.class||type==Long.class)return parsing?"int_parsing":"int_type";
        if(type==double.class||type==float.class||type!=null&&Number.class.isAssignableFrom(type))return parsing?"float_parsing":"float_type";
        return "model_attributes_type";
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Envelope> wrongParameter(HttpServletRequest request,MethodArgumentTypeMismatchException exception){
        boolean path=exception.getParameter().hasParameterAnnotation(org.springframework.web.bind.annotation.PathVariable.class);
        return error(request,422,"validation_error","Request validation failed",List.of(Map.of("field",(path?"path.":"query.")+exception.getName(),"type",valueType(exception.getRequiredType(),true))));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Envelope> malformed(HttpServletRequest request,HttpMessageNotReadableException exception) {
        byte[] bytes=bodyBytes(request);
        if(bytes.length==0||new String(bytes,java.nio.charset.StandardCharsets.UTF_8).isBlank())
            return error(request,422,"validation_error","Request validation failed",List.of(Map.of("field","body","type","missing")));
        tools.jackson.core.JacksonException decoding=null;
        for(Throwable cause=exception.getCause();cause!=null;cause=cause.getCause())if(cause instanceof tools.jackson.core.JacksonException value){decoding=value;break;}
        String field="body",type="json_invalid";
        if(decoding!=null){
            for(var part:decoding.getPath())field+="."+ (part.getPropertyName()!=null?snake(part.getPropertyName()):part.getIndex());
            if(decoding instanceof tools.jackson.databind.exc.MismatchedInputException mismatch)type=valueType(mismatch.getTargetType(),true);
            else if(decoding instanceof tools.jackson.databind.exc.ValueInstantiationException value)type=valueType(value.getType().getRawClass(),true);
            else if(decoding.getLocation()!=null){
                long offset=decoding.getLocation().getByteOffset();
                int length=(int)Math.min(bytes.length,Math.max(0,offset));
                String prefix=new String(bytes,0,length,java.nio.charset.StandardCharsets.UTF_8);
                field+="."+prefix.codePointCount(0,prefix.length());
            }
        }
        return error(request,422,"validation_error","Request validation failed",List.of(Map.of("field",field,"type",type)));
    }
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    ResponseEntity<Envelope> parameterValidation(HttpServletRequest request, org.springframework.web.method.annotation.HandlerMethodValidationException exception) {
        var fields = new java.util.ArrayList<Map<String,String>>();
        for (var result : exception.getParameterValidationResults()) {
            var parameter = result.getMethodParameter();
            var query = parameter.getParameterAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
            var path = parameter.getParameterAnnotation(org.springframework.web.bind.annotation.PathVariable.class);
            String name = parameter.getParameterName();
            if (query != null) name = !query.name().isEmpty() ? query.name() : !query.value().isEmpty() ? query.value() : name;
            if (path != null) name = !path.name().isEmpty() ? path.name() : !path.value().isEmpty() ? path.value() : name;
            for (var violation : result.getResolvableErrors()) {
                var codes = java.util.Arrays.asList(violation.getCodes() == null ? new String[0] : violation.getCodes());
                String type = codes.stream().anyMatch(code -> code.equals("Max")) ? "less_than_equal"
                    : codes.stream().anyMatch(code -> code.equals("Min")) ? ("cursor".equals(name) ? "greater_than" : "greater_than_equal")
                    : codes.stream().anyMatch(code -> code.equals("Pattern")) ? "string_pattern_mismatch" : "invalid";
                fields.add(Map.of("field", (path == null ? "query." : "path.") + name, "type", type));
            }
        }
        return error(request, 422, "validation_error", "Request validation failed", fields);
    }
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<Envelope> constraintValidation(HttpServletRequest request, jakarta.validation.ConstraintViolationException exception) {
        var fields=new java.util.ArrayList<Map<String,String>>();
        for(var violation:exception.getConstraintViolations()){
            String property=violation.getPropertyPath().toString();String name=property.substring(property.lastIndexOf('.')+1);
            String code=violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            String type=code.equals("Max")?"less_than_equal":code.equals("Min")?("cursor".equals(name)?"greater_than":"greater_than_equal"):"invalid";
            fields.add(Map.of("field","query."+name,"type",type));
        }
        return error(request, 422, "validation_error", "Request validation failed", fields);
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Envelope> uploadLimit(HttpServletRequest request) {
        return error(request, 413, "upload_too_large", "单个文件不能超过 10 MiB", null);
    }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<Envelope> missingPart(HttpServletRequest request, org.springframework.web.multipart.support.MissingServletRequestPartException exception) {
        return error(request, 422, "validation_error", "Request validation failed",
            List.of(Map.of("field", "body." + exception.getRequestPartName(), "type", "missing")));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<Envelope> dependency(HttpServletRequest request) {
        return error(request, 503, "dependencies_unavailable", "Database or cache is unavailable", null);
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Envelope> notFound(HttpServletRequest request) {
        return error(request, 404, "not_found", "Not Found", null);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Envelope> unexpected(HttpServletRequest request, Exception exception) {
        // Exception messages and stack traces can contain request values or provider credentials.
        log.error("unhandled_error exception_type={}", exception.getClass().getSimpleName());
        return error(request, 500, "internal_error", "An unexpected error occurred", null);
    }
}
