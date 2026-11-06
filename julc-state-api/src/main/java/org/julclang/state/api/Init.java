package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The constructor: {@code public static S init(args... [, Ctx])}. Exactly one per contract; its return type is the
 * state type. Deploying an instance mints its thread token and checks the initial datum equals this method's result.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Init {}
