package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ends an owned object: {@code public static JulcList<Payout> name(R object, [@Shared S shared,] args..., Ctx)}.
 * Burns its token; the payouts must allocate everything it holds.
 * <p>
 * On a keyed type (ADR-0016 D10), {@code retire = true} declares the retiring exit: the object stays as a retired node
 * holding its deposit and its key (taken for good); the payouts allocate exactly {@code ctx.balance()}, everything but
 * the deposit. A retiring exit needs nobody else, so it cannot take {@code @Shared}, {@code @Reads}, {@code @Payable},
 * {@code @Quorum}, {@code @AuthorizedBy}, {@code @ValidAfter} or {@code @ValidBefore}. Without {@code retire}, a keyed
 * object's destroy removes it from the list and frees its key.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface OwnedDestroy {
    /** A keyed type's retiring exit (ADR-0016 D10). */
    boolean retire() default false;
}
