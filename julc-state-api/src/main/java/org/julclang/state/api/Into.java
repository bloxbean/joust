package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the destination of a move (ADR-0012 D3): an {@code @OwnedAction} parameter of the state type, after the
 * {@code @Shared} source if any. The object moves to that instance, which is spent in the same transaction with an
 * {@link Admits} action naming the object; the method gets the destination's state as it was before. The redeemer
 * carries the destination's id, and both validators check each other's redeemer (I43).
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Into {}
