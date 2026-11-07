package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A method parameter that reads another JOUST contract's instance state through a reference input (ADR-0011 D2).
 * {@code value} names a {@link ForeignInstance} field of the {@link Params} record ({@code "params.oracle"}); the
 * parameter's type is that contract's state record. The validator checks the token kind, the single token, the
 * script address and the inline datum's shape before decoding.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Reads {
    String value();
}
