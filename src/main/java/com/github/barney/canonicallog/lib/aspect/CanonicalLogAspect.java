package com.github.barney.canonicallog.lib.aspect;

import com.github.barney.canonicallog.lib.context.CanonicalLogContext;
import com.github.barney.canonicallog.lib.context.ObservabilityContext;
import com.github.barney.canonicallog.lib.masking.SensitiveMasker;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
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

    private Object summarizeResult(Object result) {
        if (result == null) return "null";
        if (result instanceof Optional<?> optional) return optional.isPresent() ? "present" : "empty";
        if (result instanceof Collection<?> collection) return "collection:" + collection.size();
        if (result instanceof String string) return string.length() > 100 ? string.substring(0, 100) + "..." : string;
        return result.getClass().getSimpleName();
    }

}
