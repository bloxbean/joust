package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gives an owned method the instance's state, read-only, through a reference input: it is not spent, so it causes no
 * contention (ADR-0003 D1). The parameter's type must be the state record.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Shared {}
