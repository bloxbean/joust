package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Creates an owned object: {@code public static R name([@Shared S shared,] args... [, Ctx])}. The object belongs to
 * the instance it is created under.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface OwnedInit {}
