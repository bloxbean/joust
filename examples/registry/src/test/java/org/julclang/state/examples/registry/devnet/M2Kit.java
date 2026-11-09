package org.julclang.state.examples.registry.devnet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Relay;
import org.julclang.state.runtime.SlotChainTime;
import org.julclang.state.runtime.StateRuntime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** DevKit runtimes for the keyed-object tests (ADR-0016 M2): relays of the test's choice, and every built transaction kept. */
final class M2Kit {

    private M2Kit() {}

    /** A transaction the runtime built. */
    record Built(String what, Transaction tx) {
        String hash() {
            return TransactionUtil.getTxHash(tx);
        }

        long validFrom() {
            return tx.getBody().getValidityStartInterval();
        }

        long validTo() {
            return tx.getBody().getTtl();
        }

        int size() {
            try {
                return tx.serialize().length;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** A runtime and what it built. */
    record Rig(StateRuntime runtime, List<Built> built, SlotChainTime time, BackendService backend) {
        List<Built> built(String what) {
            synchronized (built) {
                return built.stream().filter(b -> b.what().equals(what)).toList();
            }
        }
    }

    /** As {@link DevKit#runtime()}, sending through {@code relays(backend)} (the backend alone if null). */
    static Rig rig(Function<BackendService, List<Relay>> relays) {
        return rig(relays, 3);
    }

    /** As {@link #rig(Function)}, rebuilding after up to {@code maxRetries} definite rejections. */
    static Rig rig(Function<BackendService, List<Relay>> relays, int maxRetries) {
        BackendService backend = DevKit.backend();
        SlotChainTime time = DevKit.chainTime(backend);
        var built = Collections.synchronizedList(new ArrayList<Built>());
        var b = StateRuntime.builder(backend)
                .network(Networks.testnet())
                .chainTime(time)
                .ttl(Duration.ofSeconds(20))
                .maxRetries(maxRetries)
                .log(msg -> System.out.println("  [joust] " + msg))
                .onBuilt(x -> built.add(new Built(x.what(), x.tx())))
                .evaluator(new JulcTransactionEvaluator(new DefaultUtxoSupplier(backend.getUtxoService()),
                        new DefaultProtocolParamsSupplier(backend.getEpochService()),
                        new DefaultScriptSupplier(backend.getScriptService()),
                        new SlotConfig(time.zeroSlot(), time.zeroTimeMs(), time.slotLengthMs())));
        if (relays != null) b.relays(relays.apply(backend));
        return new Rig(b.build(), built, time, backend);
    }

    static Rig rig() {
        return rig(null);
    }

    /** An account funded with {@code utxos} separate UTxOs of {@code ada} ADA each (a designated input per operation). */
    static Account wallet(int utxos, long ada) {
        Account a = DevKit.fundedAccount(ada);
        for (int i = 1; i < utxos; i++) DevKit.topUp(a.baseAddress(), ada);
        return a;
    }

    /** True once the transaction is on chain. */
    static boolean onChain(BackendService backend, String hash) {
        try {
            var r = backend.getTransactionService().getTransaction(hash);
            return r.isSuccessful() && r.getValue() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** The inputs every one of {@code attempts} spends (D7: at least the designated input). */
    static java.util.Set<String> sharedInputs(List<Built> attempts) {
        java.util.Set<String> shared = null;
        for (Built b : attempts) {
            var ins = new java.util.HashSet<String>();
            for (var in : b.tx().getBody().getInputs()) ins.add(in.getTransactionId() + "#" + in.getIndex());
            if (shared == null) shared = ins;
            else shared.retainAll(ins);
        }
        return shared == null ? java.util.Set.of() : shared;
    }

    /**
     * Spends every lovelace-only UTxO of {@code a} in one plain transaction back to itself (and waits for it): whatever
     * input an operation of {@code a} designated is then spent elsewhere.
     */
    static String spendAll(BackendService backend, Account a) {
        try {
            var utxos = backend.getUtxoService().getUtxos(a.baseAddress(), 100, 1).getValue().stream()
                    .filter(u -> u.getAmount().size() == 1 && u.getInlineDatum() == null && u.getReferenceScriptHash() == null)
                    .toList();
            var tx = new com.bloxbean.cardano.client.quicktx.Tx()
                    .collectFrom(utxos)
                    .payToAddress(a.baseAddress(), com.bloxbean.cardano.client.api.model.Amount.ada(2))
                    .withChangeAddress(a.baseAddress())
                    .from(a.baseAddress());
            var r = new com.bloxbean.cardano.client.quicktx.QuickTxBuilder(backend).compose(tx)
                    .withSigner(com.bloxbean.cardano.client.function.helper.SignerProviders.signerFrom(a))
                    .completeAndWait(Duration.ofSeconds(60));
            if (!r.isSuccessful()) throw new IllegalStateException("spendAll: " + r.getResponse());
            return r.getValue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
