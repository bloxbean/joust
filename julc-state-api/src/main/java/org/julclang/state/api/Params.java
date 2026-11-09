package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the record of a contract's deploy-time parameters (ADR-0009 D1). They are applied to the script (JuLC
 * partial application), so each parameter set has its own policy id and address. A method receives them by declaring
 * a parameter of this type right after the state (or after {@code @Shared}; first in {@code @Init}).
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Params {
}
