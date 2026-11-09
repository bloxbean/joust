package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A rule every state must keep (ADR-0009 D4): a static method taking the state (or the owned record), optionally the
 * {@link Params} record, and returning {@code boolean}. The validator checks it on every state it accepts as an
 * output (creation and every action); the runtime checks it before submitting.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Invariant {
}
