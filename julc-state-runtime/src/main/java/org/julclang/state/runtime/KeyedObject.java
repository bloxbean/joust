package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.api.RequireException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One keyed object (ADR-0016 D7): live, or retired (taken for good, no calls). Keyed actions (D6) and the retire (D10)
 * spend only its own UTxO; a removing destroy (D5) also relinks its predecessor, so it waits for the predecessor's
 * cooldown and may race its neighbours.
 *
 * @param <S> the instance state
 * @param <R> the keyed record
 */
public final class KeyedObject<S, R> {

    /**
     * The burn redeemer a generated client passes to {@link Call#destroy} for a keyed destroy: none is needed, since the
     * runtime builds a remove's {@code Remove_T} mint redeemer itself and a retire burns nothing (R-D11).
     */
    public static final Object RUNTIME_BURN = new Object() {
        @Override
        public String toString() {
            return "built by the keyed runtime";
        }
    };

    private final KeyedList<S, R> list;
    private final byte[] key;

    KeyedObject(KeyedList<S, R> list, byte[] key) {
        this.list = list;
        this.key = key.clone();
    }

    public byte[] key() { return key.clone(); }
    public String keyHex() { return HexUtil.encodeHexString(key); }
    public String unit() { return list.unit(key); }
    public KeyedList<S, R> list() { return list; }

    KeyedList.Listed<R> listed() {
        return list.lookup(key).orElseThrow(() -> new InstanceNotFoundException(list.contract().spec.name() + " "
                + list.type.name() + " " + keyHex() + " not found (the provider returned nothing)"));
    }

    public Utxo utxo() { return listed().utxo(); }

    /** The record; fails for a retired object, which keeps only its key. */
    public R value() {
        var n = listed().node();
        if (n.kind() != KeyedType.Node.Kind.LIVE) throw new RequireException(list.type.name() + " " + keyHex() + " is retired");
        return n.value();
    }

    /** Retired: the key is taken for good, the object takes no calls (D10). */
    public boolean retired() {
        return listed().node().kind() == KeyedType.Node.Kind.RETIRED;
    }

    public BigInteger lovelace() { return listed().lovelace(); }

    private String what(Call<R> call) {
        return list.contract().spec.name() + "." + call.name;
    }

    // ------------------------------------------------------------------ keyed actions (D6)

    /** A keyed {@code @OwnedAction}: the object continues with the same instance, next, linkedAt and key (I50). */
    public CallResult<R> call(Call<R> call) {
        return KeyedObjects.operation(list.contract().runtime, call.caller, what(call), d -> attempt(call, d, Mode.ACTION));
    }

    /** A removing {@code @OwnedDestroy} (D5): burns the token, frees the key, relinks the predecessor. */
    public CallResult<R> remove(Call<R> call) {
        if (list.type.remove() == null) throw new IllegalStateException(list.type.name() + " has no removing destroy");
        return KeyedObjects.operation(list.contract().runtime, call.caller, what(call), d -> attempt(call, d, Mode.REMOVE));
    }

    /** The retiring {@code @OwnedDestroy(retire = true)} (D10): needs no neighbour; the key stays taken. */
    public CallResult<R> retire(Call<R> call) {
        return KeyedObjects.operation(list.contract().runtime, call.caller, what(call), d -> attempt(call, d, Mode.RETIRE));
    }

    private enum Mode { ACTION, REMOVE, RETIRE }

    /** Control flow: a remove waited for a page's start limit and prepares again; not a retry (F60, R-C17). */
    private static final class PageWait extends RuntimeException {
        PageWait() {
            super(null, null, false, false);
        }
    }

    private CallResult<R> attempt(Call<R> call, Designation.Designated d, Mode mode) {
        for (int waits = 0; ; waits++) {
            try {
                return attemptOnce(call, d, mode);
            } catch (PageWait w) {
                if (waits >= 16) throw new JoustException(what(call) + ": still outside the page's start limit after 16 waits");
            }
        }
    }

    private CallResult<R> attemptOnce(Call<R> call, Designation.Designated d, Mode mode) {
        var contract = list.contract();
        var rt = contract.runtime;
        var spec = contract.spec;
        var type = list.type;
        String what = what(call);
        ChainTime time = rt.chainTime();
        byte[] inst = list.instanceBytes();
        call.refreshReads();   // a rebuild redoes the references (D7; F56)
        KeyedList.Listed<R> own = listed();
        if (own.node().kind() != KeyedType.Node.Kind.LIVE) {
            throw new RequireException(what + ": " + type.name() + " " + keyHex() + " is retired and takes no calls (D10)");
        }
        R current = own.node().value();
        byte[] callerPkh = Keys.pkh(call.caller);
        for (var rule : call.signers) {
            if (!Arrays.equals(rule.key().apply(current), callerPkh)) {
                throw new RequireException(what + ": must be called by the key in '" + rule.field() + "'");
            }
        }
        BigInteger inLovelace = own.lovelace();
        BigInteger minAda = BigInteger.valueOf(spec.minAda());
        // The removing destroy sees everything; an action and the retire everything but the deposit (I55).
        BigInteger balance = mode == Mode.REMOVE ? inLovelace : inLovelace.subtract(minAda);
        if (mode == Mode.RETIRE && call.received.signum() != 0) throw new RequireException(what + ": a retire receives nothing (I55)");

        // The predecessor, for a remove: relinked past this object, after its cooldown (D5, D7).
        KeyedList.Listed<R> prev = null;
        Window window;
        var cap = TxRunner.capture();
        if (mode == Mode.REMOVE) {
            var all = list.listing();
            prev = KeyedList.predecessor(all, keyHex());
            if (!Arrays.equals(prev.node().next(), key)) {
                throw new SubmitException(SubmitException.Kind.TRANSIENT, what
                        + ": inconsistent snapshot, the predecessor does not point here; retrying");
            }
            // The destroy's own time rules (D5 keeps them; F55, R-C6).
            var notBefore = new ArrayList<KeyedObjects.TimeBound>();
            var notAfter = new ArrayList<KeyedObjects.TimeBound>();
            for (var r : call.notBefore) notBefore.add(new KeyedObjects.TimeBound(r.field(), r.time().apply(current)));
            for (var r : call.notAfter) notAfter.add(new KeyedObjects.TimeBound(r.field(), r.time().apply(current)));
            if (cap != null && cap.forced != null) {
                window = cap.forced;
                long ready = type.cooldownMs() > 0 ? KeyedObjects.firstSlotAtOrAfter(time,
                        prev.node().linkedAt().add(BigInteger.valueOf(type.cooldownMs()))) : Long.MIN_VALUE;
                if (ready > window.fromSlot()) {
                    // The first pass saw the predecessor before it was relinked: rebuild (R-C16).
                    throw new SubmitException(SubmitException.Kind.TRANSIENT, what + ": the composition's window starts before"
                            + " the predecessor's cooldown ends (slot " + ready + "); it moved since the first pass");
                }
                if (window.toMs().subtract(window.fromMs()).compareTo(BigInteger.valueOf(type.windowMs())) > 0) {
                    throw new JoustException(what + ": the composition's window cannot fit a Link window (ADR-0016 D7)");
                }
                KeyedObjects.checkTimeRules(window, notBefore, notAfter, what);
            } else {
                long tip = time.tipSlot();
                var p = KeyedObjects.plan(time, tip, Long.MIN_VALUE, prev.node().linkedAt(), type.cooldownMs(), type.windowMs(),
                        spec.maxValidityMs(), rt.ttlMs(), rt.pageLimits());
                p = KeyedObjects.withTimeRules(time, p, notBefore, notAfter, what);
                if (rt.pageLimits() != null && cap == null && p.prepareAt() > tip) {
                    // D7 step 5: prepare only once the start is within the page's limit; then everything is read again.
                    TxRunner.sleep((p.prepareAt() - tip) * time.slotLengthMs());
                    throw new PageWait();
                }
                window = KeyedObjects.window(time, p.fromSlot(), p.toSlot());
                if (cap != null) cap.windows.add(window);
            }
        } else {
            window = call.usesTime() ? Window.open(rt, spec, call.notBefore, call.notAfter, current, what) : null;
        }
        Ctx ctx = new Ctx(callerPkh, call.received, balance,
                window == null ? BigInteger.ZERO : window.fromMs(), window == null ? BigInteger.ZERO : window.toMs());
        StateInstance<S> home = list.instance;
        Utxo sharedUtxo = call.sharedRun != null ? home.utxo() : null;
        Object sharedState = call.sharedRun != null ? home.state() : null;
        Transition<R> t = call.sharedRun != null ? call.sharedRun.apply(current, sharedState, ctx) : call.run.apply(current, ctx);

        Map<String, BigInteger> owed = new LinkedHashMap<>();
        List<Payout> payouts = new ArrayList<>();
        BigInteger total = BigInteger.ZERO;
        for (Payout p : t.payouts()) {
            if (p.lovelace().signum() <= 0) throw new RequireException(what + ": payouts must be positive");
            if (p.to().length != 28) throw new RequireException(what + ": payout recipient must be a 28-byte key hash");
            owed.merge(HexUtil.encodeHexString(p.to()), p.lovelace(), BigInteger::add);
            payouts.add(p);
            total = total.add(p.lovelace());
        }
        PlutusData redeemer = PlutusDataAdapter.convert(call.redeemerFor(ctx, t));
        Utxo ref = contract.findReferenceScript();
        var tx = TxRunner.newTx();
        if (d != null) TxRunner.collect(tx, d.utxo(), null);
        TxRunner.collect(tx, own.utxo(), redeemer).readFrom(ref);
        if (sharedUtxo != null) tx.readFrom(sharedUtxo);
        TxRunner.ExpectedState expected = null;
        var extra = new ArrayList<TxRunner.ExpectedState>();
        String ownUnit = unit();
        switch (mode) {
            case ACTION -> {
                R next = t.next();
                if (!Arrays.equals(type.keyOf(next), key)) throw new RequireException(what + ": a keyed action cannot change the key (I50)");
                BigInteger out = inLovelace.add(call.received).subtract(total);
                if (out.compareTo(minAda) < 0) throw new RequireException(what + ": payouts exceed the object's funds");
                PlutusData envelope = type.node(inst, new byte[type.maxKeyBytes()], BigInteger.TWO.pow(63), next);
                if (KeyedObjects.datumBytes(envelope) > spec.maxDatumBytes()) {
                    throw new RequireException(what + ": the object's datum, with next padded, exceeds maxDatumBytes (ADR-0016 D2)");
                }
                PlutusData datum = type.node(inst, own.node().next(), own.node().linkedAt(), next);
                tx.payToContract(contract.address, List.of(Amount.lovelace(out), new Amount(ownUnit, BigInteger.ONE)), datum);
                expected = new TxRunner.ExpectedState(ownUnit, out, datum);
            }
            case RETIRE -> {
                if (total.compareTo(balance) != 0) {
                    throw new RequireException(what + ": a retire pays out exactly everything but the deposit (" + balance
                            + " lovelace), but pays " + total + " (I55)");
                }
                PlutusData retired = type.retired(inst, own.node().next(), own.node().linkedAt(), key);
                // minAda, topped up by the builder when a raised coinsPerUTxOByte puts the ledger minimum above it (D10).
                BigInteger keep = KeyedObjects.topUp(rt, minAda, contract.minUtxoFor(contract.address, retired,
                        List.of(Amount.lovelace(minAda), new Amount(ownUnit, BigInteger.ONE))), what, "the retired object");
                tx.payToContract(contract.address, List.of(Amount.lovelace(keep), new Amount(ownUnit, BigInteger.ONE)), retired);
                expected = new TxRunner.ExpectedState(ownUnit, keep, retired);
            }
            case REMOVE -> {
                if (total.compareTo(inLovelace.add(call.received)) != 0) {
                    throw new RequireException(what + ": removing an object must pay out everything it holds ("
                            + inLovelace.add(call.received) + " lovelace), but pays " + total);
                }
                TxRunner.collect(tx, prev.utxo(), PlutusDataAdapter.convert(type.link()));
                TxRunner.mint(tx, contract.policyId, List.of(new Asset("0x" + HexUtil.encodeHexString(type.keyId(inst, key)),
                        BigInteger.ONE.negate())), PlutusDataAdapter.convert(type.remove().apply(prev.ref(), own.ref())));
                String prevUnit = list.unit(prev.node().key());
                PlutusData relinked = KeyedType.relinked(prev.node().datum(), own.node().next(), window.toMs());
                BigInteger prevLovelace = KeyedObjects.topUp(rt, prev.lovelace(), contract.minUtxoFor(contract.address, relinked,
                        List.of(Amount.lovelace(prev.lovelace()), new Amount(prevUnit, BigInteger.ONE))), what, "the relinked predecessor");
                tx.payToContract(contract.address, List.of(Amount.lovelace(prevLovelace), new Amount(prevUnit, BigInteger.ONE)), relinked);
                expected = new TxRunner.ExpectedState(prevUnit, prevLovelace, relinked);
            }
        }
        // Payouts, tagged with the object's input; each padded to the ledger minimum (the payout check asks >= owed).
        PlutusData tag = PlutusDataAdapter.convert(own.ref());
        List<TxRunner.ExpectedPayout> expectedPayouts = new ArrayList<>();
        for (var e : owed.entrySet()) {
            String addr = contract.payoutAddress(HexUtil.decodeHexString(e.getKey()), call.caller);
            BigInteger amount = KeyedObjects.topUp(rt, e.getValue(), contract.minUtxo(addr, tag, e.getValue()), what, "a payout");
            tx.payToContract(addr, List.of(Amount.lovelace(amount)), tag);
            expectedPayouts.add(new TxRunner.ExpectedPayout(addr, e.getValue(), tag));
        }
        List<byte[]> signers = new ArrayList<>();
        if (call.usesCtx || !call.signers.isEmpty()) signers.add(callerPkh);
        for (Account a : call.extraSigners()) {
            byte[] k = Keys.pkh(a);
            if (signers.stream().noneMatch(s -> Arrays.equals(s, k))) signers.add(k);
        }
        call.applyAuthorization(tx);
        call.applyReads(tx);
        var runner = new TxRunner(rt, call.extraSigners());
        runner.extraExpected = extra;
        runner.designated = d;
        if (d != null) d.noteScriptInputs(mode == Mode.REMOVE ? List.of(own.utxo(), prev.utxo()) : List.of(own.utxo()));
        String txHash = runner.submit(tx, call.caller, window, signers.toArray(new byte[0][]), expected, expectedPayouts,
                new PlutusScript[]{contract.script}, what);
        if (cap == null) {
            if (mode == Mode.REMOVE) awaitGone(txHash);
            else list.awaitHeld(key, txHash);
        }
        var events = new ArrayList<Object>();
        if (t.events() != null) t.events().forEach(events::add);
        return new CallResult<>(txHash, mode == Mode.ACTION ? t.next() : null, payouts, events);
    }

    private void awaitGone(String txHash) {
        for (int i = 0; i < 60; i++) {
            if (list.lookup(key).isEmpty()) return;
            TxRunner.sleepOrPending(500, txHash, "the removed " + list.type.name());
        }
        throw new PendingTransactionException(txHash, "included, but the removed " + list.type.name() + " is still visible");
    }
}
