package org.julclang.state.api;

import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ContextsLib;

/** On-chain variant of {@code Require} (see src/main/java). Bundled for JuLC in place of the JVM source. */
@OnchainLibrary
public class Require {

    public static void that(boolean condition, String message) {
        if (!condition) {
            ContextsLib.trace(message);
            Builtins.error();
        }
    }
}
