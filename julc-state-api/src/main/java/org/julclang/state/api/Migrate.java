package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A creation that adopts an instance of the previous contract version (ADR-0011 D4):
 * {@code (Params cfg, Old.State old, args..., [Ctx])} returning the new state. {@code from} names a {@code byte[]}
 * field of the params holding the previous policy. The seed is the old state UTxO, whose token must be burned in the
 * same transaction; the new instance holds exactly the old lovelace and the new token.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Migrate {
    String from();
}
