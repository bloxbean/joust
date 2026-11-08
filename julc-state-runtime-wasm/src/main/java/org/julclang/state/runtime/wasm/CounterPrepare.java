package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.common.model.Networks;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;
import org.julclang.state.examples.counter.CounterClient;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.PreparedTx;
import org.julclang.state.runtime.SlotChainTime;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.WalletAccount;
import org.julclang.state.runtime.snapshot.Need;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.julclang.state.runtime.snapshot.SnapshotBackend;

import java.math.BigInteger;
import java.time.Duration;

/**
 * Gate S0 (ADR-0015): a Counter call prepared from a snapshot, the path a page or Node app will run in Wasm
 * ({@code Compose.prepare()}, TxRunner, CCL's QuickTx with JuLC's evaluator) over a {@link SnapshotBackend}. A read
 * outside the snapshot ends the pass with its {@link Need}. M2 replaces the Counter dispatch with descriptor-driven
 * calls.
 */
public final class CounterPrepare {

    private CounterPrepare() {}

    /**
     * The call ({@code method}: init, increment, reset, close; {@code by} for increment), the wallet's address, the
     * snapshot (its JSON) and the chain's slot clock and the runtime's TTL.
     */
    public record Request(String snapshot, long zeroSlot, long zeroTimeMs, long slotLengthMs, long ttlMs,
                          String method, String instance, String by, String address) {}

    /** The unsigned transaction, or the first read the snapshot lacks, or the refusal. */
    public record Result(boolean ok, String cbor, String txHash, String instance, String needKind, String needKey,
                         String error) {}

    public static Result prepare(Request r) {
        try {
            var backend = new SnapshotBackend(Snapshot.fromJson(r.snapshot()));
            var time = new SlotChainTime(r.zeroSlot(), r.zeroTimeMs(), r.slotLengthMs(), backend);
            var rt = StateRuntime.builder(backend)
                    .network(Networks.testnet())
                    .chainTime(time)
                    .ttl(Duration.ofMillis(r.ttlMs()))
                    .evaluator(new JulcTransactionEvaluator(
                            new DefaultUtxoSupplier(backend.getUtxoService()),
                            new DefaultProtocolParamsSupplier(backend.getEpochService()),
                            new DefaultScriptSupplier(backend.getScriptService()),
                            new SlotConfig(time.zeroSlot(), time.zeroTimeMs(), time.slotLengthMs())))
                    .build();
            var wallet = WalletAccount.of(r.address(), Networks.testnet());
            String[] made = new String[1];
            PreparedTx p = Compose.of(rt, wallet).add(() -> {
                switch (r.method()) {
                    case "init" -> made[0] = CounterClient.deploy(rt, wallet).instanceId();
                    case "increment" -> CounterClient.at(rt, r.instance()).increment(wallet, new BigInteger(r.by()));
                    case "reset" -> CounterClient.at(rt, r.instance()).reset(wallet);
                    case "close" -> CounterClient.at(rt, r.instance()).close(wallet);
                    default -> throw new IllegalArgumentException("Counter has no call " + r.method());
                }
            }).prepare();
            return new Result(true, p.cbor(), p.txHash(), made[0], null, null, null);
        } catch (Throwable t) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof Need n) return new Result(false, null, null, null, n.kind(), n.key(), null);
            }
            return new Result(false, null, null, null, null, null, String.valueOf(t.getMessage() != null ? t.getMessage() : t));
        }
    }
}
