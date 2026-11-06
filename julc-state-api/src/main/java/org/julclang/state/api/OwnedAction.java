package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Changes one owned object: {@code public static R name(R object, [@Shared S shared,] args... [, Ctx])}, or returns
 * {@code (R value, JulcList<Payout> payouts)}. Spends only the object's UTxO.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface OwnedAction {}
