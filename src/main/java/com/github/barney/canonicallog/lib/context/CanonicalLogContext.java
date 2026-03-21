package com.github.barney.canonicallog.lib.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.barney.canonicallog.lib.masking.SensitiveMasker;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;

// Logging context object
public class CanonicalLogContext {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

    private final Instant startTime = Instant.now();
    private String httpMethod;
    private String httpPath;
    private String correlationId;
    private Map<String, String> requestHeaders;
    private Object requestBody;
    private int httpStatus;
    private Object responseBody;
    private final List<LogEvent> events = new CopyOnWriteArrayList<>(); // Listing of all accumulated events

    public sealed interface LogEvent permits MethodLogEvent, EntityLogEvent, OutboundLogEvent, ErrorLogEvent {
        Instant timestamp();
    }

    public record MethodLogEvent (Instant timestamp, String className, String method, Map<String, Object> args, Object result, long durationMs, String error) implements LogEvent {}
    public record EntityLogEvent (Instant timestamp, String entityType, String entityId, String operation) implements LogEvent {}
    public record OutboundLogEvent (Instant timestamp, String service, String endpoint, String httpMethod, int statusCode, long durationMs, String error) implements LogEvent {}
    public record ErrorLogEvent (Instant timestamp, String phase, String errorType, String message, String stackSnippet) implements LogEvent {}

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public void setHttpPath(String httpPath) {
        this.httpPath = httpPath;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getCorrelationId() {
        return this.correlationId;
    }


    public void setRequestHeaders(Map<String, String> requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    public void setRequestBody(Object requestBody) {
        this.requestBody = requestBody;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public void setResponseBody(Object responseBody) {
        this.responseBody = responseBody;
    }

    public void addEvent(LogEvent event) {
        events.add(event);
    }

    // Emit as key=value text pair
    public void emitTextLogEvent(Logger log) {
        try {
            long totalMs = Duration.between(startTime, Instant.now()).toMillis();
            String outcome = deriveOutcome();
            String failureReason = deriveFailureReason();

            StringBuilder sb = new StringBuilder("canonical-log");
            sb.append(" http_method=").append(httpMethod);
            sb.append(" http_path=").append(httpPath);
            sb.append(" http_status=").append(httpStatus);
            sb.append(" correlation_id=").append(correlationId);
            sb.append(" outcome=").append(outcome);
            if (failureReason != null) {
                sb.append(" failure_reason=\"").append(sanitize(failureReason)).append("\"");
            }
            sb.append(" duration_ms=").append(totalMs);
            sb.append(" step_count=").append(events.size());
            sb.append(" steps=[");

            StringJoiner sj = new StringJoiner(", ");
            for (LogEvent e : events) {
                sj.add(summarizeText(e));
            }
            sb.append(sj);
            sb.append("]");

            log.info(sb.toString());
        } catch (Exception e) {
            log.error("Failed to emit canonical log line", e);
        }
    }

    // Emit as structured JSON  (ELK, Loki)
    public void emitJsonLogEvent(Logger log) {
        try {
            long totalMs = Duration.between(startTime, Instant.now()).toMillis();

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("log_type", "canonical-log-line");
            line.put("http_method", httpMethod);
            line.put("http_path", httpPath);
            line.put("http_status", httpStatus);
            line.put("correlation_id", correlationId);
            line.put("outcome", deriveOutcome());
            line.put("failure_reason", deriveFailureReason());
            line.put("duration_ms", totalMs);
            line.put("request_headers", maskHeaders(requestHeaders));
            line.put("step_count", events.size());
            line.put("steps", events.stream().map(this::toMap).toList());

            String json = MAPPER.writeValueAsString(line);
            log.info(json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize canonical log line", e);
        }
    }

    private String deriveOutcome() {
        boolean hasError = events.stream().anyMatch(e ->
                (e instanceof MethodLogEvent ms && ms.error() != null) ||
                        (e instanceof OutboundLogEvent os && os.error() != null) ||
                        (e instanceof ErrorLogEvent));
        if (hasError) return "FAILED";
        if (httpStatus >= 400) return "FAILED";
        return "SUCCESS";
    }

    private String deriveFailureReason() {
        return events.stream()
                .filter(e -> e instanceof ErrorLogEvent)
                .map(e -> ((ErrorLogEvent) e).message())
                .findFirst()
                .orElseGet(() -> events.stream()
                        .filter(e -> e instanceof MethodLogEvent ms && ms.error() != null)
                        .map(e -> ((MethodLogEvent) e).error())
                        .findFirst()
                        .orElseGet(() -> events.stream()
                                .filter(e -> e instanceof OutboundLogEvent os && os.error() != null)
                                .map(e -> ((OutboundLogEvent) e).error())
                                .findFirst()
                                .orElse(null)));
    }

    private String summarizeText(LogEvent event) {
        return switch (event) {
            case MethodLogEvent m -> "{method=%s.%s, duration_ms=%d, status=%s}".formatted(
                    m.className(), m.method(), m.durationMs(),
                    m.error() == null ? "ok" : "error:" + truncate(m.error(), 80));
            case EntityLogEvent e -> "{entity=%s, op=%s, id=%s}".formatted(
                    e.entityType(), e.operation(), e.entityId());
            case OutboundLogEvent o -> "{service=%s, endpoint=%s, http=%s, status=%d, duration_ms=%d%s}".formatted(
                    o.service(), o.endpoint(), o.httpMethod(), o.statusCode(), o.durationMs(),
                    o.error() != null ? ", error=" + truncate(o.error(), 60) : "");
            case ErrorLogEvent err -> "{phase=%s, error=%s, message=%s}".formatted(
                    err.phase(), err.errorType(), truncate(err.message(), 80));
        };
    }

    private Map<String, Object> toMap(LogEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (event) {
            case MethodLogEvent ms -> {
                m.put("type", "method");
                m.put("class", ms.className());
                m.put("method", ms.method());
                m.put("args", ms.args());
                m.put("result", ms.result());
                m.put("duration_ms", ms.durationMs());
                m.put("error", ms.error());
            }
            case EntityLogEvent es -> {
                m.put("type", "entity");
                m.put("entity", es.entityType());
                m.put("id", es.entityId());
                m.put("operation", es.operation());
            }
            case OutboundLogEvent os -> {
                m.put("type", "outbound");
                m.put("service", os.service());
                m.put("endpoint", os.endpoint());
                m.put("http_method", os.httpMethod());
                m.put("status_code", os.statusCode());
                m.put("duration_ms", os.durationMs());
                m.put("error", os.error());
            }
            case ErrorLogEvent err -> {
                m.put("type", "error");
                m.put("phase", err.phase());
                m.put("error_type", err.errorType());
                m.put("message", err.message());
            }
        }
        return m;
    }

    private Map<String, String> maskHeaders(Map<String, String> headers) {
        if (headers == null) return Map.of();
        Map<String, String> masked = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (SensitiveMasker.isSensitive(k)) {
                masked.put(k, "****");
            } else {
                masked.put(k, v);
            }
        });
        return masked;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String sanitize(String s) {
        if (s == null) return null;
        return SensitiveMasker.maskInString(s.replace("\"", "'"));
    }

}
