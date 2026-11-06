package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The transaction's validity interval must start at or after the time (POSIX ms, {@code BigInteger}) held in the
 * named field of the current state.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ValidAfter {
    /** Name of a {@code BigInteger} component of the state record. */
    String value();
}
