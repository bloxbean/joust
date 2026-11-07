package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An {@code @Action} through which an instance admits an object moving in from another instance of the same contract
 * (ADR-0012 D3). Its first argument after the state (and the params) is the object's id ({@code byte[]}). The validator
 * requires that object to be spent in the same transaction with a move ({@link Into}) naming this instance; the method
 * decides whether to admit it and may update the instance's state.
 * <p>
 * {@code value} names the owned record it admits ({@code @Admits("Hero")}): only moves of that type can arrive through
 * it, and it can only be a move of that type that it admits. It may be left out when only one owned type has moves.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Admits {
    String value() default "";
}
