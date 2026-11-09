package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ends an owned object: {@code public static JulcList<Payout> name(R object, [@Shared S shared,] args..., Ctx)}.
 * Burns its token; the payouts must allocate everything it holds.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface OwnedDestroy {}
