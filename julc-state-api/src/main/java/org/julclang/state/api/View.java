package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A read-only query, {@code public static T name(S state, args...)}. Runs off-chain only.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface View {}
