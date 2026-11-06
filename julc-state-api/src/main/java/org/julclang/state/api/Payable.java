package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The {@link Init} or {@link Action} may receive lovelace ({@code ctx.received() > 0}). Requires a {@link Ctx}
 * parameter. Without it, {@code received} must be zero.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Payable {}
