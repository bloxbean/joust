package org.julclang.state.api;

import org.julclang.state.api.Require;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;
import org.julclang.stdlib.lib.CryptoLib;

/**
 * Audited adapter for {@code .jsc}'s {@code std/crypto} Ed25519 (ADR-0018 D8, F18): the builtin fails for a key that
 * is not 32 bytes or a signature that is not 64, where the JVM provider answers false; this adapter fails there on both
 * sides, with a {@code require}, and otherwise answers as the builtin does.
 */
@OnchainLibrary
public class CryptoAdapter {

    /** Whether {@code sig} is {@code key}'s Ed25519 signature of {@code msg}; key 32 bytes, signature 64. */
    public static boolean verifyEd25519(byte[] key, byte[] msg, byte[] sig) {
        Require.that(ByteStringLib.length(key) == 32, "verifyEd25519: the key must be 32 bytes");
        Require.that(ByteStringLib.length(sig) == 64, "verifyEd25519: the signature must be 64 bytes");
        return CryptoLib.verifyEd25519Signature(key, msg, sig);
    }
}
