package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@link Destroy}: the instance migrates to its successor contract (ADR-0011 D4). {@code value} names a
 * {@code byte[]} field holding the successor's policy: a state field an admin sets, or {@code params.*}. The validator
 * requires that the successor mints the instance id {@code blake2b_256(own input)}, and that its output holds at least
 * everything this instance holds, less the payouts.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface MigrateTo {
    String value();
}
