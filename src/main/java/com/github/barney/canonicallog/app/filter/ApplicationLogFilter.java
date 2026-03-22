package com.github.barney.canonicallog.app.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.barney.canonicallog.lib.context.CanonicalLogContext;
import com.github.barney.canonicallog.lib.context.ObservabilityContext;
import com.github.barney.canonicallog.lib.masking.SensitiveMasker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * The outermost layer of canonical logging.
 *
 * Create a CanonicalLogContext and bind it via ScopedValue
 * Capture HTTP request metadata (method, path, headers, body)
 * Let the filter chain execute (controllers, services, etc and accumulate events)
 * Capture HTTP response metadata (status, body)
 * Emit the canonical log line in a finally block
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApplicationLogFilter extends OncePerRequestFilter {

    private static final Logger canonicalLog = LoggerFactory.getLogger("canonical");
    private static final ObjectMapper bodyMapper = new ObjectMapper();

    @Value("${canonical.log.format:text}")
    private String logFormat;

    @Value("${canonical.log.include-bodies:true}")
    private boolean includeHttpBodies;


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        // contentCacheLimit to 1MB (1 * 1024 * 1024 bytes)
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, 1048576);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        CanonicalLogContext logContext = new CanonicalLogContext();

        logContext.setHttpMethod(request.getMethod());
        logContext.setHttpPath(request.getRequestURI());
        logContext.setCorrelationId(extractOrGenerateCorrelationId(request));
        logContext.setRequestHeaders(extractHeaders(request));
        logContext.setSourceSystem(extractSourceSystem(request));

        ScopedValue.where(ObservabilityContext.CANONICAL_LOG_SCOPE, logContext)
                .run(() -> {

                    try {
                        filterChain.doFilter(requestWrapper, responseWrapper);
                    } catch (Exception exception) {
                        logContext.addEvent(
                                new CanonicalLogContext.ErrorLogEvent(
                                        Instant.now(), "filter_chain",
                                        exception.getClass().getSimpleName(), exception.getMessage(),
                                        getStackTraceSnippet(exception)
                                )
                        );
                        logContext.setHttpStatus(500);

                        // rethrow to satisfy the lambda
                        if (exception instanceof RuntimeException runtimeException) throw runtimeException;
                        throw new RuntimeException(exception);
                    } finally {

                        logContext.setHttpStatus(responseWrapper.getStatus());

                        if (includeHttpBodies) {
                            logContext.setRequestBody(getBody(requestWrapper.getContentAsByteArray()));
                            logContext.setResponseBody(getBody(responseWrapper.getContentAsByteArray()));
                        }

                        if ("json".equalsIgnoreCase(logFormat)) {
                            logContext.emitJsonLogEvent(canonicalLog);
                        } else {
                            logContext.emitTextLogEvent(canonicalLog);
                        }

                        try {
                            responseWrapper.copyBodyToResponse();
                        } catch (IOException e) {
                            // response may already be committed
                        }
                    }
                });
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/h2-console") || path.startsWith("/favicon.ico") ||path.startsWith("/mock") ;
    }

    private String extractOrGenerateCorrelationId(HttpServletRequest request) {
        String id = request.getHeader("X-Correlation-Id");
        if (id == null) id = request.getHeader("X-Request-Id");
        return id != null ? id : UUID.randomUUID().toString();
    }

    private String extractSourceSystem(HttpServletRequest request) {
        String sourceSystem = request.getHeader("X-Source-System");
        return sourceSystem != null ? sourceSystem : "";
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private Map<String, Object> getBody(byte[] content) {
        if (content == null || content.length == 0) return null;

        String raw = new String(content, StandardCharsets.UTF_8);

        if (raw.length() > 4000) {
            raw = raw.substring(0, 4000);
        }

        String trimmed = raw.strip();

        if (trimmed.startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = bodyMapper.readValue(trimmed, LinkedHashMap.class);
                return maskBodyFields(parsed);
            } catch (Exception e) {
                return Map.of(
                        "_raw", truncate(raw),
                        "_parse_error", e.getClass().getSimpleName() + ": " + e.getMessage()
                );
            }
        }

        if (trimmed.startsWith("[")) {
            try {
                Object parsed = bodyMapper.readValue(trimmed, Object.class);
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("_type", "array");
                if (parsed instanceof List<?> list) {
                    wrapped.put("_size", list.size());
                    // Include up to 5 items
                    wrapped.put("_items", list.stream()
                            .limit(5)
                            .map(item -> {
                                if (item instanceof Map<?, ?> map) {
                                    return maskBodyFields(toStringKeyMap(map));
                                }
                                return item;
                            })
                            .toList());
                    if (list.size() > 5) wrapped.put("_truncated", true);
                }
                return wrapped;
            } catch (Exception e) {
                return Map.of("_raw", truncate(raw));
            }
        }

        return Map.of("_raw", truncate(raw));
    }

    private String getStackTraceSnippet(Exception exception) {
        if (exception.getStackTrace().length == 0) return "";
        StringBuilder stringBuilder = new StringBuilder();
        int limit = Math.min(3, exception.getStackTrace().length);
        for (int index = 0; index < limit; index++) {
            stringBuilder.append(exception.getStackTrace()[index].toString());
            if (index < limit - 1) stringBuilder.append(" → ");
        }
        return stringBuilder.toString();
    }

    private Map<String, Object> maskBodyFields(Map<String, Object> body) {
        Map<String, Object> masked = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            if (SensitiveMasker.isSensitive(key)) {
                masked.put(key, value != null ? SensitiveMasker.maskValue(value.toString()) : null);
            } else if (value instanceof Map<?, ?> nested) {
                masked.put(key, maskBodyFields(toStringKeyMap(nested)));
            } else if (value instanceof List<?> list) {
                masked.put(key, list.stream()
                        .map(item -> item instanceof Map<?, ?> m ? maskBodyFields(toStringKeyMap(m)) : item)
                        .toList());
            } else if (value instanceof String s && s.length() > 500) {
                masked.put(key, s.substring(0, 500) + "...[truncated]");
            } else {
                masked.put(key, value);
            }
        });
        return masked;
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private String truncate(String s) {
        return (s != null && s.length() > 2000) ? s.substring(0, 2000) + "...[truncated]" : s;
    }
}

