package com.github.barney.canonicallog.lib.aspect;

import com.github.barney.canonicallog.lib.context.CanonicalLogContext;
import com.github.barney.canonicallog.lib.context.ObservabilityContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.Instant;


@Aspect
@Component
@Order(5)
public class CanonicalRepositoryAspect {

    @Around("execution(* org.springframework.data.repository.CrudRepository+.save(..)) || " +
            "execution(* org.springframework.data.jpa.repository.JpaRepository+.saveAndFlush(..))")
    public Object interceptSave(ProceedingJoinPoint jointPoint) throws Throwable {

        CanonicalLogContext context = ObservabilityContext.logContext();
        Object entity = jointPoint.getArgs().length > 0 ? jointPoint.getArgs()[0] : null;

        String operation = "Save";
        String entityType = "unknown";
        String entityId = "unknown";

        if (context != null && entity != null) {
            entityType = entity.getClass().getSimpleName();
            entityId = extractId(entity);
            operation = (entityId == null || "null".equals(entityId)) ? "INSERT" : "UPDATE";
        }

        Object result = jointPoint.proceed(); // original save

        if (context != null && entity != null) {
            // record log event here with entity generated id
            String resolvedId = operation.equals("INSERT")
                    ? extractId(result != null ? result : entity)
                    : entityId;

            context.addEvent(new CanonicalLogContext.EntityLogEvent(Instant.now(), entityType, resolvedId, operation));
        }

        return result;
    }

    @Around("execution(* org.springframework.data.repository.CrudRepository+.delete(..)) || " +
            "execution(* org.springframework.data.repository.CrudRepository+.deleteById(..))")
    public Object interceptDelete(ProceedingJoinPoint jointPoint) throws Throwable {

        CanonicalLogContext context = ObservabilityContext.logContext();
        Object entity = jointPoint.getArgs().length > 0 ? jointPoint.getArgs()[0] : null;

        String entityType = "Unknown";
        String entityId = "unknown";

        if (entity != null) {
            if (entity instanceof String || entity instanceof Number) {
                // deleteById(id), arg is id
                entityId = entity.toString();
                entityType = inferEntityTypeFromRepository(jointPoint);
            } else {
                // delete(entity), arg is entity
                entityType = entity.getClass().getSimpleName();
                entityId = extractId(entity);
            }
        }

        Object result = jointPoint.proceed();

        if (context != null) {
            context.addEvent(new CanonicalLogContext.EntityLogEvent(Instant.now(), entityType, entityId, "DELETE"));
        }

        return result;
    }

    private String extractId(Object entity) {
        // Extract entity ID looks for fields annotated with @Id or named id
        try {

            for (Field field : entity.getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    field.setAccessible(true);
                    Object id = field.get(entity);
                    return id != null ? id.toString() : "null";
                }
            }

            Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);

            Object id = idField.get(entity);
            return id != null ? id.toString() : "null";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String inferEntityTypeFromRepository(ProceedingJoinPoint jointPoint) {
        // infer the entity type from the repository interface name, RepaymentTransactionRepository → RepaymentTransaction
        try {
            Class<?>[] interfaces = jointPoint.getTarget().getClass().getInterfaces();
            for (Class<?> iFace : interfaces) {
                String name = iFace.getSimpleName();
                if (name.endsWith("Repository")) {
                    return name.replace("Repository", "");
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }
}
