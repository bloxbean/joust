package org.julclang.state.api;

import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ContextsLib;

/**
 * Transition-program variant of {@code Require} (ADR-0020 D9, adopted in §11.4), compiled only into transition programs,
 * never into validators: a refusal traces {@code joust:require <message>} before failing, so the engine tells a
 * contract's refusal from any other failure by this tag, never by the last trace. It differs from the on-chain source
 * (src/onchain/java) only in the traced text.
 */
@OnchainLibrary
public class Require {

    public static void that(boolean condition, String message) {
        if (!condition) {
            ContextsLib.trace("joust:require " + message);
            Builtins.error();
        }
    }
}
