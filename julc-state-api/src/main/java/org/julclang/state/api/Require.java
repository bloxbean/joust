package org.julclang.state.api;

import org.julclang.stdlib.annotation.OnchainLibrary;

/**
 * Contract rules. {@code Require.that(cond, "message")} fails the call when {@code cond} is false.
 * <p>
 * On the JVM it throws {@link RequireException} carrying the message, so the client reports why a call was rejected
 * before submitting anything. On-chain (the bundled source in {@code src/onchain/java}) it traces the message and
 * fails the script. Both fail on exactly the same inputs (ADR-0001 D7).
 */
@OnchainLibrary
public class Require {

    public static void that(boolean condition, String message) {
        if (!condition) {
            throw new RequireException(message);
        }
    }
}
