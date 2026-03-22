package com.github.barney.canonicallog.lib.aspect;

import com.github.barney.canonicallog.lib.context.CanonicalLogContext;
import com.github.barney.canonicallog.lib.context.ObservabilityContext;
import com.github.barney.canonicallog.lib.masking.SensitiveMasker;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

// Intercepts methods with @Tracked annotations and records their execution into log context
@Aspect
@Component
@Order(10)
public class CanonicalLogAspect {

    @Around("@annotation(tracked)")
    public Object trackExecution(ProceedingJoinPoint jointPoint, Tracked tracked) throws Throwable {

        CanonicalLogContext context = ObservabilityContext.logContext();
        if (context == null) {
            return jointPoint.proceed();
        }

        String className = jointPoint.getTarget().getClass().getSimpleName();
        String methodName = tracked.value().isEmpty()
                ? jointPoint.getSignature().getName()
                : tracked.value();

        Set<String> maskedArgs = Set.of(tracked.maskArgs());
        Map<String, Object> capturedMaskedArgs = captureArguments(jointPoint, maskedArgs);

        Instant start = Instant.now();

        try {

            Object result = jointPoint.proceed();
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            context.addEvent(new CanonicalLogContext.MethodLogEvent(
                    start, className, methodName, capturedMaskedArgs, summarizeResult(result), durationMs, null
            ));

            return result;

        } catch (Throwable t) {

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            context.addEvent(new CanonicalLogContext.MethodLogEvent(
                    start, className, methodName, capturedMaskedArgs, null, durationMs,
                    t.getClass().getSimpleName() + ": " + t.getMessage()
            ));

            // No exception swallowing
            throw t;
        }
    }

    private Map<String, Object> captureArguments(ProceedingJoinPoint jointPoint, Set<String> maskedArguments) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        try {
            MethodSignature methodSignature = (MethodSignature) jointPoint.getSignature();
            String[] names = methodSignature.getParameterNames();
            Object[] values = jointPoint.getArgs();

            if (names == null) return arguments;

            for (int i = 0; i < names.length; i++) {
                Object value = values[i];
                if (maskedArguments.contains(names[i]) || SensitiveMasker.isSensitive(names[i])) {
                    arguments.put(names[i], value != null ? SensitiveMasker.maskValue(value.toString()) : null);
                } else {
                    arguments.put(names[i], sanitizeValue(value));
                }
            }
        } catch (Exception e) {
            arguments.put("_capture_error", e.getMessage());
        }
        return arguments;
    }

    private Object sanitizeValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) {
            return string.length() > 200 ? string.substring(0, 200) + "...[truncated]" : string;
        }
        if (value instanceof byte[] byteArray) return "[binary:" + ((byte[]) byteArray).length + "B]";
        if (value instanceof Map<?, ?> map) return "[map:" + map.size() + "]";
        if (value instanceof Collection<?> collection) return "[collection:" + collection.size() + "]";

        String valueString = value.toString();
        if (valueString.length() > 300) {
            return value.getClass().getSimpleName() + "[truncated]";
        }
        return valueString;
    }

    private Map<String, Object> summarizeResult(Object result) {
        Map<String, Object> summary = new LinkedHashMap<>();

        if (result == null) {
            summary.put("type", "null");
            return summary;
        }
        summary.put("type", result.getClass().getSimpleName());

        switch (result) {
            case Optional<?> optional -> {
                if (optional.isPresent()) {
                    summary.put("body", extractBody(optional.get()));
                } else {
                    summary.put("body", "empty");
                }
            }
            case Collection<?> collection -> {
                summary.put("size", collection.size());
                if (collection.size() <= 5) {
                    summary.put("body", collection.stream().map(this::extractBody).toList());
                } else {
                    // show first 3 items 4 large collections
                    summary.put("body", collection.stream().limit(3).map(this::extractBody).toList());
                    summary.put("truncated", true);
                }
            }
            case Map<?, ?> map -> {
                summary.put("size", map.size());
                summary.put("body", SensitiveMasker.maskMap(
                        map.entrySet().stream()
                                .limit(10)
                                .collect(LinkedHashMap::new,
                                        (m, e) -> m.put(String.valueOf(e.getKey()), e.getValue()),
                                        LinkedHashMap::putAll)));
            }
            case String s -> {
                summary.put("body", s.length() > 200
                        ? s.substring(0, 200) + "...[truncated]"
                        : s);
            }
            case Number n -> summary.put("body", n);
            case Boolean b -> summary.put("body", b);
            case Enum<?> e -> summary.put("body", e.name());
            case ResponseEntity<?> re -> {
                summary.put("status", re.getStatusCode().value());
                if (re.getBody() != null) {
                    summary.put("body", extractBody(re.getBody()));
                }
            }
            default -> summary.put("body", extractBody(result));
        }

        return summary;
    }

    private Object extractBody(Object objectInstance) {
        if (objectInstance == null) return "null";
        if (objectInstance instanceof String s) return s.length() > 200 ? s.substring(0, 200) + "..." : s;
        if (objectInstance instanceof Number || objectInstance instanceof Boolean || objectInstance instanceof Enum<?>) return objectInstance;

        Map<String, Object> fields = new LinkedHashMap<>();

        try {
            if (objectInstance.getClass().isRecord()) {
                for (var component : objectInstance.getClass().getRecordComponents()) {
                    String name = component.getName();
                    Object value = component.getAccessor().invoke(objectInstance);
                    fields.put(name, maskAndSanitize(name, value));
                }
            } else {
                for (var field : objectInstance.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    if (java.lang.reflect.Modifier.isTransient(field.getModifiers())) continue;

                    field.setAccessible(true);
                    String name = field.getName();
                    Object value = field.get(objectInstance);
                    fields.put(name, maskAndSanitize(name, value));
                }
            }
        } catch (Exception e) {
            String str = objectInstance.toString();
            return str.length() > 300 ? str.substring(0, 300) + "...[truncated]" : str;
        }

        return fields.isEmpty() ? objectInstance.toString() : fields;
    }

    // Mask sensitive values 4 safe logging, sanitize large objects from bloating the log
    private Object maskAndSanitize(String fieldName, Object value) {
        if (value == null) return null;

        // Mask sensitive fields
        if (SensitiveMasker.isSensitive(fieldName)) {
            return SensitiveMasker.maskValue(value.toString());
        }

        return switch (value) {
            case String string -> string.length() > 200 ? string.substring(0, 200) + "...[truncated]" : string;
            case Number number -> number;
            case Boolean b -> b;
            case Enum<?> e -> e.name();
            case byte[] bytes -> "[binary:" + bytes.length + "B]";
            case Collection<?> collection -> "[collection:" + collection.size() + "]";
            case Map<?, ?> map  -> "[map:" + map.size() + "]";
            default -> {
                String str = value.toString();
                yield str.length() > 200 ? str.substring(0, 200) + "...[truncated]" : str;
            }
        };
    }

}
