package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An action that takes deposits from other JOUST contracts in the same transaction (ADR-0011 D3). Its value equation
 * adds every deposit claimed on its continuing output, and the method sees them in {@code funds.deposits()} (it must
 * take {@link Funds}) to decide what to credit. An action without it takes no deposits: its exact value check fails if
 * anyone adds value to its output.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Receives {}
