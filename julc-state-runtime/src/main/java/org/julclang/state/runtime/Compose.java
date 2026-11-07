package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;

import java.util.ArrayList;
import java.util.List;

/**
 * Several JOUST calls in one atomic transaction (ADR-0011 D1). Each call is an ordinary client call, run inside the
 * composition: the first pass learns every call's validity window, the second runs every call in their intersection,
 * so every Ctx equals the final bounds. The calls write into one transaction; the output registry gets every send's
 * claim at its final index; every required signer signs.
 * <pre>{@code
 * String tx = Compose.of(runtime, payer)
 *         .add(() -> shop.buy(buyer, qty, price))
 *         .add(() -> oracle.post(publisher, newPrice))
 *         .submit();
 * }</pre>
 * Refused before submitting:
 * <ul>
 *   <li>the same UTxO spent by two calls;</li>
 *   <li>two calls minting under one policy;</li>
 *   <li>an empty common window;</li>
 *   <li>a key that signs for more than one call, unless {@link #allowSharedSigners()} is called. A signature counts
 *   for every {@code @SignedBy}/{@code @Quorum} in the transaction.</li>
 * </ul>
 * Each validator still checks only its own transition, so composing adds no trust.
 */
public final class Compose {

    private final StateRuntime runtime;
    private final Account payer;
    private final List<Runnable> calls = new ArrayList<>();
    private boolean sharedSigners;

    private Compose(StateRuntime runtime, Account payer) {
        this.runtime = runtime;
        this.payer = payer;
    }

    public static Compose of(StateRuntime runtime, Account payer) {
        return new Compose(runtime, payer);
    }

    /** Adds a call: any client call (action, destroy, deploy, owned call), run inside the composition. */
    public Compose add(Runnable call) {
        calls.add(call);
        return this;
    }

    /** Allows one key's signature to count for several calls (refused by default; ADR-0011 r2). */
    public Compose allowSharedSigners() {
        this.sharedSigners = true;
        return this;
    }

    /** Builds, checks, submits and confirms the one transaction; returns its hash. */
    public String submit() {
        if (calls.isEmpty()) throw new IllegalStateException("nothing to compose");
        // Pass 1: every call's own window.
        var probe = new TxRunner.Capture();
        run(probe);
        Window window = probe.windows.isEmpty() ? null : Window.intersect(runtime, probe.windows);
        if (!probe.windows.isEmpty() && window == null) {
            throw new JoustException("composition: the calls' validity windows do not overlap");
        }
        // Pass 2: every call in the common window, into one transaction.
        var cap = new TxRunner.Capture();
        cap.forced = window;
        run(cap);
        if (!sharedSigners) {
            for (var e : cap.signerCalls.entrySet()) {
                if (e.getValue().size() > 1) {
                    throw new JoustException("composition: key " + e.getKey() + " signs for " + e.getValue()
                            + "; its signature would count for every call (allowSharedSigners() to accept)");
                }
            }
        }
        if (!cap.claims.isEmpty()) {
            var sorted = new ArrayList<>(cap.claims);
            sorted.sort(java.util.Comparator.comparing(org.julclang.state.api.OutputClaim::output));
            org.julclang.core.types.JulcList<org.julclang.state.api.OutputClaim> list = org.julclang.core.types.JulcList.empty();
            for (int i = sorted.size() - 1; i >= 0; i--) list = list.prepend(sorted.get(i));
            runtime.outputVerifier().attachRedeemer(cap.tx, new org.julclang.state.api.OutputClaims(list), payer);
            cap.scripts.putIfAbsent("output-verifier", runtime.outputVerifier().script());
        }
        var signers = new ArrayList<Account>(cap.accounts.values());
        signers.removeIf(a -> a.baseAddress().equals(payer.baseAddress()));
        var runner = new TxRunner(runtime, signers);
        runner.extraExpected = cap.expected.size() > 1 ? cap.expected.subList(1, cap.expected.size()) : List.of();
        return runner.submit(cap.tx, payer, window, cap.requiredSigners.values().toArray(new byte[0][]),
                cap.expected.isEmpty() ? null : cap.expected.get(0), cap.payouts, cap.sends,
                cap.scripts.values().toArray(new com.bloxbean.cardano.client.plutus.spec.PlutusScript[0]), "composed transaction");
    }

    private void run(TxRunner.Capture cap) {
        TxRunner.CAPTURE.set(cap);
        try {
            for (Runnable call : calls) call.run();
        } finally {
            TxRunner.CAPTURE.remove();
        }
    }
}
