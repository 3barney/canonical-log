package com.github.barney.canonicallog.lib.context;

import com.github.barney.canonicallog.lib.masking.SensitiveMasker;
import net.logstash.logback.marker.Markers;
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

    private final Instant startTime = Instant.now();
    private String httpMethod;
    private String httpPath;
    private String correlationId;
    private String sourceSystem;
    private Map<String, String> requestHeaders;
    private Object requestBody;
    private int httpStatus;
    private Object responseBody;
    private final List<LogEvent> events = new CopyOnWriteArrayList<>(); // Listing of all accumulated events

    public sealed interface LogEvent permits MethodLogEvent, EntityLogEvent, OutboundLogEvent, ErrorLogEvent {
        Instant timestamp();
    }

    public record MethodLogEvent (Instant timestamp, String className, String method, Map<String, Object> args, Map<String, Object> result, long durationMs, String error) implements LogEvent {}
    public record EntityLogEvent (Instant timestamp, String entityType, String entityId, String operation) implements LogEvent {}
    public record OutboundLogEvent (Instant timestamp, String service, String endpoint, String httpMethod, int statusCode, long durationMs, Map<String, String> requestHeaders, Map<String, String> responseHeaders, String error) implements LogEvent {}
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

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getSourceSystem() {
        return this.sourceSystem;
    }

    public void setRequestHeaders(Map<String, String> requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    public void setRequestBody(Map<String, Object> requestBody) {
        this.requestBody = requestBody;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public void setResponseBody(Map<String, Object> responseBody) {
        this.responseBody = responseBody;
    }

    public void addEvent(LogEvent event) {
        events.add(event);
    }

    // Emit as key=value text pair
    public void emitTextLogEvent(Logger log) {
        try {
            long total = Duration.between(startTime, Instant.now()).toMillis();
            String outcome = deriveOutcome();
            String failureReason = deriveFailureReason();

            StringBuilder stringBuilder = new StringBuilder("canonical-log-line");
            stringBuilder.append(" http_method=").append(httpMethod);
            stringBuilder.append(" start_time").append(startTime);
            stringBuilder.append(" http_path=").append(httpPath);
            stringBuilder.append(" http_status=").append(httpStatus);
            stringBuilder.append(" correlation_id=").append(correlationId);
            stringBuilder.append(" source_system=").append(sourceSystem);
            stringBuilder.append(" outcome=").append(outcome);
            if (failureReason != null) {
                stringBuilder.append(" failure_reason=\"").append(sanitize(failureReason)).append("\"");
            }
            stringBuilder.append(" duration_ms=").append(total);
            stringBuilder.append(" request_headers=").append(maskHeaders(requestHeaders));
            stringBuilder.append(" request_body").append(requestBody);
            stringBuilder.append(" response_body").append(responseBody);
            stringBuilder.append(" step_count=").append(events.size());
            stringBuilder.append(" steps=[");

            StringJoiner sj = new StringJoiner(", ");
            for (LogEvent e : events) {
                sj.add(summarizeText(e));
            }
            stringBuilder.append(sj);
            stringBuilder.append("]");

            log.info(stringBuilder.toString());
        } catch (Exception e) {
            log.error("Failed to emit canonical log line", e);
        }
    }

    // Emit as structured JSON  (ELK, Loki)
    public void emitJsonLogEvent(Logger log) {
        long totalMs = Duration.between(startTime, Instant.now()).toMillis();

        Map<String, Object> logLine = new LinkedHashMap<>();
        logLine.put("log_type", "canonical-log-line");
        logLine.put("http_method", httpMethod);
        logLine.put("start_time", startTime.toString());
        logLine.put("http_path", httpPath);
        logLine.put("http_status", httpStatus);
        logLine.put("correlation_id", correlationId);
        logLine.put("source_system", sourceSystem);
        logLine.put("outcome", deriveOutcome());
        logLine.put("failure_reason", deriveFailureReason());
        logLine.put("duration_ms", totalMs);
        logLine.put("request_headers", maskHeaders(requestHeaders));
        logLine.put("request_body", requestBody);
        logLine.put("response_body", responseBody);
        logLine.put("step_count", events.size());
        logLine.put("steps", events.stream().map(this::toMap).toList());

        log.info(Markers.appendEntries(logLine), "canonical-log-line");
    }

    private String deriveOutcome() {
        boolean hasError = events.stream().anyMatch(logEvent ->
                (logEvent instanceof MethodLogEvent methodLogEvent && methodLogEvent.error() != null) ||
                        (logEvent instanceof OutboundLogEvent outboundLogEvent && outboundLogEvent.error() != null) ||
                        (logEvent instanceof ErrorLogEvent));

        if (hasError) return "FAILED";
        if (httpStatus >= 400) return "FAILED";
        return "SUCCESS";
    }

    private String deriveFailureReason() {
        return events.stream()
                .filter(ErrorLogEvent.class::isInstance)
                .map(logEvent -> ((ErrorLogEvent) logEvent).message())
                .findFirst()
                .orElseGet(() -> events.stream()
                        .filter(logEvent -> logEvent instanceof MethodLogEvent methodLogEvent && methodLogEvent.error() != null)
                        .map(logEvent -> ((MethodLogEvent) logEvent).error())
                        .findFirst()
                        .orElseGet(() -> events.stream()
                                .filter(logEvent -> logEvent instanceof OutboundLogEvent outboundLogEvent && outboundLogEvent.error() != null)
                                .map(logEvent -> ((OutboundLogEvent) logEvent).error())
                                .findFirst()
                                .orElse(null)));
    }

    private String summarizeText(LogEvent event) {
        return switch (event) {
            case MethodLogEvent methodLogEvent -> "{method=%s.%s, duration_ms=%d, status=%s}".formatted(
                    methodLogEvent.className(), methodLogEvent.method(), methodLogEvent.durationMs(),
                    methodLogEvent.error() == null ? "ok" : "error:" + truncate(methodLogEvent.error(), 80));

            case EntityLogEvent entityLogEvent -> "{entity=%s, op=%s, id=%s}".formatted(
                    entityLogEvent.entityType(), entityLogEvent.operation(), entityLogEvent.entityId());

            case OutboundLogEvent outboundLogEvent -> "{service=%s, endpoint=%s, http=%s, status=%d, duration_ms=%d%s}".formatted(
                    outboundLogEvent.service(), outboundLogEvent.endpoint(), outboundLogEvent.httpMethod(), outboundLogEvent.statusCode(), outboundLogEvent.durationMs(),
                    outboundLogEvent.error() != null ? ", error=" + truncate(outboundLogEvent.error(), 60) : "");

            case ErrorLogEvent errorLogEvent -> "{phase=%s, error=%s, message=%s}".formatted(
                    errorLogEvent.phase(), errorLogEvent.errorType(), truncate(errorLogEvent.message(), 80));
        };
    }

    private Map<String, Object> toMap(LogEvent event) {
        Map<String, Object> logLineEvent = new LinkedHashMap<>();
        switch (event) {
            case MethodLogEvent methodLogEvent -> {
                logLineEvent.put("type", "method");
                logLineEvent.put("class", methodLogEvent.className());
                logLineEvent.put("method", methodLogEvent.method());
                logLineEvent.put("args", methodLogEvent.args());
                logLineEvent.put("result", methodLogEvent.result());
                logLineEvent.put("duration_ms", methodLogEvent.durationMs());
                logLineEvent.put("error", methodLogEvent.error());
            }
            case EntityLogEvent entityLogEvent -> {
                logLineEvent.put("type", "entity");
                logLineEvent.put("entity", entityLogEvent.entityType());
                logLineEvent.put("id", entityLogEvent.entityId());
                logLineEvent.put("operation", entityLogEvent.operation());
            }
            case OutboundLogEvent outboundLogEvent -> {
                logLineEvent.put("type", "outbound");
                logLineEvent.put("service", outboundLogEvent.service());
                logLineEvent.put("endpoint", outboundLogEvent.endpoint());
                logLineEvent.put("http_method", outboundLogEvent.httpMethod());
                logLineEvent.put("status_code", outboundLogEvent.statusCode());
                logLineEvent.put("duration_ms", outboundLogEvent.durationMs());
                logLineEvent.put("request_headers", maskHeaders(outboundLogEvent.requestHeaders));
                logLineEvent.put("response_headers", maskHeaders(outboundLogEvent.responseHeaders));
                logLineEvent.put("error", outboundLogEvent.error());
            }
            case ErrorLogEvent errorLogEvent -> {
                logLineEvent.put("type", "error");
                logLineEvent.put("phase", errorLogEvent.phase());
                logLineEvent.put("error_type", errorLogEvent.errorType());
                logLineEvent.put("message", errorLogEvent.message());
            }
        }
        return logLineEvent;
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
