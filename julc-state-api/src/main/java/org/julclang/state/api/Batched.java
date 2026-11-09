package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The {@link Action} may also run as an <b>intent</b> (ADR-0004): a user locks a request in their own UTxO, and a
 * batcher applies many requests to the instance in one transaction, so concurrent writers do not contend.
 * <p>
 * In a batch, {@code ctx.caller()} is the intent's owner (who signed when creating it), {@code ctx.received()} the
 * amount the intent carries, {@code ctx.balance()} the contract's balance at that point of the fold, and
 * {@code validFrom}/{@code validTo} the batch transaction's bounds. The action needs a {@link Ctx} parameter and
 * cannot use {@code @SignedBy}, {@code @OnlyOwner}, {@code @ValidAfter} or {@code @ValidBefore}: authorise with
 * {@code ctx.caller()} and check time with {@link Clock}.
 * <p>
 * The batcher chooses which intents to include and, by choosing the validity range, when they run. An intent runs
 * against whatever state the batch finds: take a bound (a minimum out, a maximum price) as an argument and check it.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Batched {
    /** Only as an intent: no direct call, so nobody can invalidate pending batches by spending the instance directly. */
    boolean only() default false;
}
