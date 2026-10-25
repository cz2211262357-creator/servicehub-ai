package cn.servicehub.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {
    private final RuntimeMetrics metrics;
    public RequestIdFilter(RuntimeMetrics metrics){this.metrics=metrics;}
    public static final String ATTRIBUTE = "servicehub.request_id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{8,96}");
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var candidate = request.getHeader("x-request-id");
        var id = candidate != null && SAFE.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("x-request-id", id);
        response.setHeader("x-content-type-options", "nosniff");
        var previous = MDC.get("request_id");
        MDC.put("request_id", id);
        long started = System.nanoTime();
        // Retain a bounded, request-local JSON copy solely for safe validation locations.
        // Never cache uploads or log this body (it may contain a password).
        HttpServletRequest observed=request;
        String contentType=request.getContentType();
        if(contentType!=null&&contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")
            &&java.util.Set.of("POST","PUT","PATCH").contains(request.getMethod()))
            observed=new org.springframework.web.util.ContentCachingRequestWrapper(request,65536);
        try { chain.doFilter(observed, response); }
        finally {
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            log.info("request_completed method={} route={} status={} duration_ms={}", request.getMethod(),
                route == null ? "unmatched" : route, response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            if(request.getRequestURI().matches("/api/v1/conversations/[1-9][0-9]*/interrupt"))
                metrics.record("cancel","ai_reply",response.getStatus()<400?"success":"failed",java.time.Duration.ofNanos(System.nanoTime()-started));
            if (previous == null) MDC.remove("request_id"); else MDC.put("request_id", previous);
        }
    }
}
