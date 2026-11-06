package org.julclang.state.examples.counter.generated;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.state.examples.counter.Counter;
import org.julclang.clientlib.JulcScriptLoader;
import org.julclang.clientlib.PlutusDataAdapter;

import java.math.BigInteger;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * ============================================================================
 *  GENERATED from {@link Counter} by the (proposed) @StateContract processor.
 *  Do not edit. Hand-written in this prototype to show the exact output.
 * ============================================================================
 *
 * Typed client for one Counter instance. One method per @Init / @Action / @View.
 * Built on cardano-client-lib (QuickTx). The developer never touches UTxOs, datums
 * or redeemers, but can drop down to CCL via the {@code *Tx} methods.
 *
 * In the real framework, the generic parts (state lookup, retry, submit) would live in
 * a shared {@code julc-state-runtime} library. Here they're inlined so the example is
 * self-contained.
 */
public final class CounterClient {

    static final String STATE_TOKEN_HEX = HexUtil.encodeHexString("STATE".getBytes());
    static final BigInteger STATE_DEPOSIT = BigInteger.valueOf(2_000_000);   // min-ADA buffer held by the state UTxO
    static final int MAX_RETRIES = 3;

    private final BackendService backend;
    private final TransactionEvaluator evaluator;   // optional; e.g. JuLC local evaluator
    private final PlutusV3Script script;
    private final String scriptAddress;
    private final String stateUnit;                 // policyId + "STATE"
    private final String seedTxHash;
    private final int seedIndex;

    private CounterClient(BackendService backend, TransactionEvaluator evaluator, String seedTxHash, int seedIndex) {
        this.backend = backend;
        this.evaluator = evaluator;
        this.seedTxHash = seedTxHash;
        this.seedIndex = seedIndex;
        this.script = JulcScriptLoader.load(CounterValidator.class,
                new BytesPlutusData(HexUtil.decodeHexString(seedTxHash)),
                BigIntPlutusData.of(seedIndex));
        this.scriptAddress = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        try {
            this.stateUnit = HexUtil.encodeHexString(script.getScriptHash()) + STATE_TOKEN_HEX;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ===================================================================== @Init

    /** Deploys a new Counter. {@code owner} pays, signs, and becomes the owner. */
    public static CounterClient deploy(BackendService backend, TransactionEvaluator evaluator, Account owner) {
        try {
            // 1. pick a seed UTxO from the owner's wallet -> makes this instance's policy id unique
            Utxo seed = backend.getUtxoService().getUtxos(owner.baseAddress(), 1, 1).getValue().get(0);
            var client = new CounterClient(backend, evaluator, seed.getTxHash(), seed.getOutputIndex());

            // 2. run the developer's @Init on the JVM
            byte[] caller = pkh(owner);
            Counter.State initial = Counter.init(caller);

            // 3. build: consume seed, mint thread token, lock it with the initial state
            var tx = new ScriptTx()
                    .collectFrom(seed)
                    .mintAsset(client.script, new Asset("0x" + STATE_TOKEN_HEX, BigInteger.ONE),
                            PlutusDataAdapter.convert(new CounterValidator.Create(caller)))
                    .payToContract(client.scriptAddress,
                            List.of(Amount.lovelace(STATE_DEPOSIT), new Amount(client.stateUnit, BigInteger.ONE)),
                            PlutusDataAdapter.convert(initial));

            client.submit(tx, owner, caller);
            return client;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("deploy failed", e);
        }
    }

    /** Reconnect to an existing instance (identified by its seed). */
    public static CounterClient at(BackendService backend, TransactionEvaluator evaluator, String seedTxHash, int seedIndex) {
        return new CounterClient(backend, evaluator, seedTxHash, seedIndex);
    }

    // ===================================================================== @Action

    public String increment(BigInteger by, Account caller) {
        byte[] who = pkh(caller);
        return runAction(caller, s -> Counter.increment(s, by, who),
                new CounterValidator.Increment(by, who));
    }

    public String reset(Account caller) {
        byte[] who = pkh(caller);
        return runAction(caller, s -> Counter.reset(s, who),
                new CounterValidator.Reset(who));
    }

    // escape hatch: get the CCL ScriptTx to compose with other txs yourself
    public ScriptTx incrementTx(BigInteger by, byte[] callerPkh) {
        return actionTx(findStateUtxo(), s -> Counter.increment(s, by, callerPkh),
                new CounterValidator.Increment(by, callerPkh));
    }

    // ===================================================================== @View

    public Counter.State state() {
        return decode(findStateUtxo());
    }

    public BigInteger count() {
        return Counter.count(state());
    }

    // ===================================================================== plumbing

    public String scriptAddress() { return scriptAddress; }
    public String seed() { return seedTxHash + "#" + seedIndex; }

    private String runAction(Account caller, UnaryOperator<Counter.State> transition,
                             CounterValidator.CounterAction redeemer) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Utxo stateUtxo = findStateUtxo();
            // Runs the developer's code on the JVM. A failing require() throws here,
            // before anything is submitted.
            ScriptTx tx = actionTx(stateUtxo, transition, redeemer);
            try {
                return submit(tx, caller, pkh(caller));
            } catch (RuntimeException e) {
                last = e;
                // Contention: someone else moved the state first. Re-read and retry.
                Utxo now = findStateUtxo();
                if (now.getTxHash().equals(stateUtxo.getTxHash()) && now.getOutputIndex() == stateUtxo.getOutputIndex()) {
                    throw e;   // not contention: a real failure
                }
                System.out.println("  state moved under us (attempt " + attempt + "), retrying...");
            }
        }
        throw last;
    }

    private ScriptTx actionTx(Utxo stateUtxo, UnaryOperator<Counter.State> transition,
                              CounterValidator.CounterAction redeemer) {
        Counter.State next = transition.apply(decode(stateUtxo));
        return new ScriptTx()
                .collectFrom(stateUtxo, PlutusDataAdapter.convert(redeemer))
                .payToContract(scriptAddress, stateUtxo.getAmount(), PlutusDataAdapter.convert(next)) // same value back
                .attachSpendingValidator(script);
    }

    private String submit(ScriptTx tx, Account signer, byte[] signerPkh) {
        var builder = new QuickTxBuilder(backend).compose(tx)
                .withSigner(SignerProviders.signerFrom(signer))
                .feePayer(signer.baseAddress())
                .collateralPayer(signer.baseAddress())
                .withRequiredSigners(signerPkh);
        if (evaluator != null) builder = builder.withTxEvaluator(evaluator);
        var result = builder.complete();
        if (!result.isSuccessful()) throw new RuntimeException("tx failed: " + result.getResponse());
        String txHash = result.getValue();
        waitForState(txHash);
        return txHash;
    }

    private Utxo findStateUtxo() {
        try {
            for (int page = 1; page < 50; page++) {
                var res = backend.getUtxoService().getUtxos(scriptAddress, 100, page);
                if (!res.isSuccessful() || res.getValue() == null || res.getValue().isEmpty()) break;
                for (Utxo u : res.getValue()) {
                    boolean hasToken = u.getAmount().stream().anyMatch(a -> a.getUnit().equals(stateUnit));
                    if (hasToken) return u;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        throw new IllegalStateException("Counter state UTxO not found at " + scriptAddress);
    }

    private void waitForState(String txHash) {
        for (int i = 0; i < 60; i++) {
            try {
                if (findStateUtxo().getTxHash().equals(txHash)) return;
            } catch (IllegalStateException ignored) { }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        throw new RuntimeException("state not updated by " + txHash + " within 30s");
    }

    private static Counter.State decode(Utxo u) {
        try {
            var data = PlutusData.deserialize(HexUtil.decodeHexString(u.getInlineDatum()));
            return PlutusDataAdapter.convert(data, Counter.State.class);
        } catch (Exception e) {
            throw new RuntimeException("cannot decode Counter state", e);
        }
    }

    private static byte[] pkh(Account a) {
        return a.hdKeyPair().getPublicKey().getKeyHash();
    }
}
