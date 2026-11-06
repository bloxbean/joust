package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The transaction must be signed by the key hash ({@code byte[]}) held in the named field of the current state.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface SignedBy {
    /** Name of a {@code byte[]} component of the state record. */
    String value();
}
