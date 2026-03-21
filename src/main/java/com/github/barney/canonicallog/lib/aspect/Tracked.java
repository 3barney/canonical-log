package com.github.barney.canonicallog.lib.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automated log capture. It records class, method, arguments (masked), result, duration, errors.
 *
 * Usage:
 *   @Tracked → uses method name
 *   @Tracked("queryMsisdn") → custom step name
 *   @Tracked(maskArgs = {"msisdn"}) → mask specific arguments
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tracked {

    String value() default "";

    String[] maskArgs() default {};
}