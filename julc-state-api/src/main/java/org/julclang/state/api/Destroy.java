package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ends the instance: {@code public static JulcList<Payout> name(S state, args... [, Ctx])}. The thread token is burned
 * and the returned payouts must allocate the contract's whole balance.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Destroy {}
