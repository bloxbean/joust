package org.julclang.state.api;

import java.math.BigInteger;

/**
 * Transaction facts an {@link Init}, {@link Action} or {@link Destroy} method may read. Declare it as the method's last
 * parameter. The client supplies it; the generated validator verifies every field against the transaction before
 * calling the method (ADR-0001 D3).
 *
 * @param caller    key hash of the party calling; the transaction is signed by it
 * @param received  lovelace this call adds to the contract (zero unless the method is {@link Payable})
 * @param balance   funds the call may allocate: in {@link Action}s, the contract's lovelace before the call minus the
 *                  min-ADA deposit; in {@link Destroy}, everything the state UTxO holds, deposit included (the instance
 *                  ends, so all of it must be paid out); zero in {@link Init}
 * @param validFrom the transaction's validity interval starts here (POSIX ms, inclusive)
 * @param validTo   the transaction's validity interval ends here (POSIX ms, exclusive)
 * <p>
 * The validator checks {@code validFrom} and {@code validTo} <em>equal</em> the transaction's finite bounds, and that
 * {@code validTo - validFrom <= @StateContract(maxValidityMs)}. The interval contains the current time, so
 * {@code validFrom} is at most that far in the past. For deadlines prefer {@link Clock#after} and {@link Clock#before}.
 */
public record Ctx(byte[] caller, BigInteger received, BigInteger balance, BigInteger validFrom, BigInteger validTo) {}
