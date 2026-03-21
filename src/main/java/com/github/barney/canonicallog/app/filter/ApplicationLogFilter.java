package com.github.barney.canonicallog.app.filter;

import com.github.barney.canonicallog.lib.context.CanonicalLogContext;
import com.github.barney.canonicallog.lib.context.ObservabilityContext;
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
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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

    private static final Logger cannonicalLog = LoggerFactory.getLogger("canonical");

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
                            logContext.emitJsonLogEvent(cannonicalLog);
                        } else {
                            logContext.emitTextLogEvent(cannonicalLog);
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
        return path.startsWith("/actuator") || path.startsWith("/h2-console") || path.startsWith("/favicon.ico");
    }

    private String extractOrGenerateCorrelationId(HttpServletRequest request) {
        String id = request.getHeader("X-Correlation-Id");
        if (id == null) id = request.getHeader("X-Request-Id");
        return id != null ? id : UUID.randomUUID().toString();
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

    private String getBody(byte[] content) {
        if (content == null || content.length == 0) return null;
        String body = new String(content, StandardCharsets.UTF_8);
        if (body.length() > 2000) {
            return body.substring(0, 2000) + "...[truncated]";
        }
        return body;
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
}

