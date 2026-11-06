package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A state transition: {@code public static S name(S state, args... [, Ctx])}, or returning a record
 * {@code (S state, JulcList<Payout> payouts)} to pay funds out of the contract.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Action {}
