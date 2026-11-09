package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Decides which certificates of the contract's credential are allowed (ADR-0010 D1): {@code (Params cfg, TxCert cert)} returning {@code boolean}. Unregistration, committee and pool certificates are refused before it runs. Needs {@code @Quorum} or {@code @AuthorizedBy} over {@code params.*}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Certify {
}
