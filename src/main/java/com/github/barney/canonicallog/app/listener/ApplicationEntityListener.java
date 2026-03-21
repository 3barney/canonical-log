package com.github.barney.canonicallog.app.listener;

import com.github.barney.canonicallog.lib.context.CanonicalLogContext;
import com.github.barney.canonicallog.lib.context.ObservabilityContext;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

import java.lang.reflect.Field;
import java.time.Instant;

/**
 * JPA entity listener automatically records persistence events into log context.
 * Register via @EntityListeners on a @MappedSuperclass, or per-entity.
 */
public class ApplicationEntityListener {

    @PostPersist
    public void onInsert(Object entity) {
        recordLog(entity, "INSERT");
    }

    @PostUpdate
    public void onUpdate(Object entity) {
        recordLog(entity, "UPDATE");
    }

    @PostRemove
    public void onDelete(Object entity) {
        recordLog(entity, "DELETE");
    }

    private void recordLog(Object entity, String operation) {

        CanonicalLogContext logContext = ObservabilityContext.logContext();
        if (logContext == null) return;

        logContext.addEvent(new CanonicalLogContext.EntityLogEvent(
                Instant.now(),
                entity.getClass().getSimpleName(),
                extractId(entity),
                operation
        ));
    }

    // Extract entity ID looks for fields annotated with @Id or named id
    private String extractId(Object entity) {
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
}
