package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Decides the contract's votes as a DRep or committee member (ADR-0010 D1): {@code (Params cfg, Voter voter, GovernanceActionId action, Vote vote)} returning {@code boolean}, called for every vote it casts. Needs {@code @Quorum} or {@code @AuthorizedBy} over {@code params.*}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Votes {
}
