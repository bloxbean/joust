package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A constitution guardrail (ADR-0010 D1): {@code (Params cfg, ProposalProcedure p)} returning {@code boolean}. The ledger runs it for parameter-change and treasury-withdrawal proposals once a constitution names this script.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Propose {
}
