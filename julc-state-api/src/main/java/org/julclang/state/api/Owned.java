package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the contract's owned record (ADR-0003): each owned object is its own UTxO, touched only by owned methods,
 * so different objects never contend. At most one per contract.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Owned {}
