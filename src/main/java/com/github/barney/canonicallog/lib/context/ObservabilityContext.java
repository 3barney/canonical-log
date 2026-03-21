package com.github.barney.canonicallog.lib.context;

// Holds the ScopedValue that carries the canonical log context.
public final class ObservabilityContext {

    /**
     * The canonical-log context for the current request bound by the HTTP filter
     */
    public static final ScopedValue<CanonicalLogContext> CANONICAL_LOG_SCOPE = ScopedValue.newInstance();

    // accessor — returns null if called outside a bound scope such as startup times and tests
    public static final CanonicalLogContext logContext() {
        return CANONICAL_LOG_SCOPE.isBound() ? CANONICAL_LOG_SCOPE.get() : null;
    }
}
