package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Handles withdrawals of the contract's staking rewards (ADR-0010 D1): {@code (Params cfg, BigInteger amount, args..., [Ctx])} returning {@code JulcList<Send>} totalling exactly {@code amount}, claimed in the output registry. Needs {@code @Quorum} or {@code @AuthorizedBy} over {@code params.*}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Withdraw {
}
