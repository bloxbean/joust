package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The method needs the approval of a credential (ADR-0009 D3), named by a {@code Credential} field of the state, the
 * owned record, or the {@link Params} record ({@code "params.field"}). A key credential must sign; a script
 * credential must run in the transaction for a withdrawal (the withdraw-zero pattern), approving it by its own rules.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface AuthorizedBy {
    String value();
}
