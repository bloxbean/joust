package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * M-of-N signatures (ADR-0009 D2): at least {@code threshold} (and at least one) of the transaction's signatories
 * are among {@code keys}. Both name fields of the state (or the owned record): {@code keys} a
 * {@code JulcList<byte[]>} of key hashes, {@code threshold} a {@code BigInteger}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Quorum {
    String keys();

    String threshold();
}
