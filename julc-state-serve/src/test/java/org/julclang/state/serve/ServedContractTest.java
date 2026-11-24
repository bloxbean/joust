package org.julclang.state.serve;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.examples.bank.BankClient;
import org.julclang.state.examples.counter.CounterClient;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.devkit.Fixtures;
import org.julclang.state.runtime.CallScope;
import org.julclang.state.runtime.ClaimStore;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.PreparedTx;
import org.julclang.state.runtime.ReservedInputException;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.WalletAccount;
import org.julclang.state.runtime.snapshot.ReadFailed;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.julclang.state.runtime.snapshot.SnapshotBackend;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0022 D16 and the runtime seams it relies on, offline over the dApp's recorded calls ({@code fixtures/m0/dapp}):
 * the served contract's view without any transport, reflection failures keeping their type (D9, F2), a failed read
 * ending a prepare as itself (D6, T8), the prepared transaction's bounds read from its body and its recorded payouts
 * (D5, D5a), and a claim store set through the builder seeing the prepare's claim and its slot (D8).
 */
class ServedContractTest {
    static final Path FIXTURES = Path.of("../fixtures/m0");
    static final ObjectMapper JSON = new ObjectMapper();
    static final ServedContract COUNTER = ServedContract.of(CounterClient.class);
    static final ServedContract BANK = ServedContract.of(BankClient.class);

    static JsonNode fixture(String name) throws Exception {
        return Fixtures.read(FIXTURES.resolve("dapp").resolve(name));
    }

    /**
     * The first recorded dApp call of {@code contract}'s {@code method}, by content: re-recording renumbers the files
     * (the JuLC 0.1.0-pre22 bump added a step before Bank's calls).
     */
    static String firstCall(String contract, String method) throws Exception {
        try (var s = java.nio.file.Files.list(FIXTURES.resolve("dapp"))) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith("-composed_transaction.json")).sorted().toList()) {
                JsonNode call = Fixtures.read(p).path("call");
                if (call.path("contract").asText().endsWith("." + contract) && call.path("method").asText().equals(method)) {
                    return p.getFileName().toString();
                }
            }
        }
        throw new AssertionError("no recorded " + contract + "." + method);
    }

    static StateRuntime runtime(BackendService backend, UnaryOperator<StateRuntime.Builder> more) {
        return DevKit.snapshotRuntime(backend, FIXTURES.resolve("devkit.json"), more);
    }

    static StateRuntime runtime(JsonNode fixture) throws Exception {
        return runtime(new SnapshotBackend(Fixtures.snapshot(fixture)), b -> b);
    }

    /** A backend over {@code fixture}'s snapshot whose address reads at {@code failing} addresses fail. */
    static BackendService failingUtxos(JsonNode fixture, Predicate<String> failing) throws Exception {
        BackendService inner = new SnapshotBackend(Fixtures.snapshot(fixture));
        UtxoService utxos = (UtxoService) Proxy.newProxyInstance(ServedContractTest.class.getClassLoader(),
                new Class<?>[]{UtxoService.class}, (p, m, a) -> {
                    if (m.getName().equals("getUtxos") && a != null && a[0] instanceof String address && failing.test(address)) {
                        throw new ReadFailed("utxos", address, "the client's provider answered 503");
                    }
                    try {
                        return m.invoke(inner.getUtxoService(), a);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return (BackendService) Proxy.newProxyInstance(ServedContractTest.class.getClassLoader(), new Class<?>[]{BackendService.class},
                (p, m, a) -> {
                    if (m.getName().equals("getUtxoService")) return utxos;
                    try {
                        return m.invoke(inner, a);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** What {@code JoustServer.prepare} does for a recorded call, without HTTP. */
    static PreparedTx prepare(StateRuntime rt, JsonNode call) {
        return prepare(rt, call, null);
    }

    private static PreparedTx prepare(StateRuntime rt, JsonNode call, String designated) {
        ServedContract c = call.get("contract").asText().endsWith(".Counter") ? COUNTER : BANK;
        String kind = call.get("kind").asText();
        JsonNode meta = c.meta(kind, call.get("method").asText());
        var wallet = WalletAccount.of(call.get("address").asText(), rt.network());
        JsonNode pay = call.path("pay");
        BigInteger lovelace = pay.has("lovelace") ? new BigInteger(pay.get("lovelace").asText()) : BigInteger.ZERO;
        String instance = call.path("instance").isTextual() ? call.get("instance").asText() : null;
        String object = call.path("object").isTextual() ? call.get("object").asText() : null;
        var composition = Compose.of(rt, wallet);
        if (designated != null) composition.designate(designated);
        return composition.add(() -> c.call(rt, wallet, kind, meta, instance, object, call.path("args"), lovelace, pay.get("assets")))
                .prepare();
    }

    // ------------------------------------------------------------------ views without a transport (D15, D16)

    @Test
    void theStateIsReadWithoutAnyTransport() throws Exception {
        JsonNode f = fixture("0003-composed_transaction.json");   // Counter.increment
        var rt = runtime(f);
        String instance = f.get("call").get("instance").asText();
        Object state = COUNTER.view(rt, instance, null, null);
        assertNotNull(state);
        assertTrue(state.getClass().isRecord(), "the generated client's state record: " + state.getClass());
        JsonNode json = COUNTER.viewJson(rt, instance, null, null);
        assertTrue(json.isObject(), json.toString());
        assertEquals(JsonValues.toJson(state), json);
        assertThrows(BadRequest.class, () -> COUNTER.view(rt, "zz", null, null));
        assertThrows(BadRequest.class, () -> COUNTER.view(rt, instance, "noSuchView", null));
    }

    @Test
    void aFailedReadInAViewIsTheReadFailure_notAWrappedRefusal() throws Exception {
        JsonNode f = fixture("0003-composed_transaction.json");
        var rt = runtime(failingUtxos(f, a -> true), b -> b);
        String instance = f.get("call").get("instance").asText();
        var e = assertThrows(ReadFailed.class, () -> COUNTER.view(rt, instance, null, null));
        assertEquals("utxos", e.kind());
        assertEquals("the client's provider answered 503", e.reason());
    }

    // ------------------------------------------------------------------ typed causes (D9, F2)

    @Test
    void reflectionWrappersAreRemoved_andJoustsOwnErrorsKeepTheirType() {
        var read = new ReadFailed("output", "ab#0", "timeout");
        assertSame(read, assertThrows(ReadFailed.class,
                () -> ServedContract.unwrapped(new InvocationTargetException(read), "m")));
        var refusal = new JoustException("refused");
        assertSame(refusal, ServedContract.unwrapped(new InvocationTargetException(refusal), "m"));
        var need = new org.julclang.state.runtime.snapshot.Need("tip", "");
        assertSame(need, assertThrows(org.julclang.state.runtime.snapshot.Need.class,
                () -> ServedContract.unwrapped(new InvocationTargetException(need), "m")));
        // Any other Error or a checked exception: wrapped as before, with it as the cause.
        var other = new AssertionError("boom");
        var wrapped = ServedContract.unwrapped(new InvocationTargetException(other), "m");
        assertInstanceOf(JoustException.class, wrapped);
        assertEquals("m failed", wrapped.getMessage());
        assertSame(other, wrapped.getCause());
    }

    @Test
    void aFailedReadEndsAWholePrepareAsItself() throws Exception {
        JsonNode f = fixture("0003-composed_transaction.json");
        String wallet = f.get("call").get("address").asText();
        // The state lookup (inside the generated client's call) and the wallet's balancing (after it) both fail typed.
        var inCall = runtime(failingUtxos(f, a -> !a.equals(wallet)), b -> b);
        assertThrows(ReadFailed.class, () -> prepare(inCall, f.get("call")));
        var inBuild = runtime(failingUtxos(f, a -> a.equals(wallet)), b -> b);
        var e = assertThrows(ReadFailed.class, () -> prepare(inBuild, f.get("call")));
        assertEquals(wallet, e.key());
    }

    // ------------------------------------------------------------------ PreparedTx (D5, D5a, F21)

    @Test
    void theBoundsAreTheBodys_evenForAnUntimedCall() throws Exception {
        JsonNode f = fixture("0003-composed_transaction.json");
        var p = prepare(runtime(f), f.get("call"));
        assertEquals(f.get("txHash").asText(), p.txHash(), "the recorded transaction");
        Transaction body = Transaction.deserialize(HexUtil.decodeHexString(p.cbor()));
        assertEquals(-1, p.validTo(), "validTo() keeps its meaning: the call chose no window");
        assertEquals(body.getBody().getTtl(), p.validToSlot().orElseThrow(), "the body's upper bound");
        assertTrue(p.validToSlot().getAsLong() > 0);
        assertTrue(p.validFromSlot().isEmpty(), "an untimed call has no lower bound");
        assertTrue(p.payouts().isEmpty());
        assertTrue(p.sends().isEmpty());
    }

    @Test
    void thePayoutsTheBuildRecorded_withWhatIsOwed() throws Exception {
        JsonNode f = fixture(firstCall("Bank", "withdraw"));   // Bank.withdraw 10 ADA
        var p = prepare(runtime(f), f.get("call"));
        assertEquals(f.get("txHash").asText(), p.txHash());
        assertEquals(1, p.payouts().size(), p.payouts().toString());
        var payout = p.payouts().get(0);
        assertEquals(BigInteger.valueOf(10_000_000), payout.owed());
        Transaction body = Transaction.deserialize(HexUtil.decodeHexString(p.cbor()));
        assertTrue(body.getBody().getOutputs().stream().anyMatch(o -> o.getAddress().equals(payout.address())
                && o.getInlineDatum() != null && o.getValue().getCoin().compareTo(payout.owed()) >= 0),
                "the body pays it, at least what is owed");
        // #145 Codex review 6: the tag is the paying output's exact datum, and names one of the body's inputs (I7).
        var paid = body.getBody().getOutputs().stream().filter(o -> o.getAddress().equals(payout.address())
                && o.getInlineDatum() != null).toList();
        assertEquals(1, paid.size());
        assertEquals(payout.tagCbor(), HexUtil.encodeHexString(paid.get(0).getInlineDatum().serializeToBytes()),
                "the tag is the output's datum, byte for byte");
        // TxOutRef as data: Constr 0 [TxId, index], the id's 32 bytes inside its first field.
        var tag = (com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData)
                com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(HexUtil.decodeHexString(payout.tagCbor()));
        assertEquals(0, tag.getAlternative());
        var fields = tag.getData().getPlutusDataList();
        assertEquals(2, fields.size());
        String id = HexUtil.encodeHexString(bytesIn(fields.get(0)));
        var index = ((com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData) fields.get(1)).getValue().intValueExact();
        assertTrue(body.getBody().getInputs().stream().anyMatch(in -> in.getTransactionId().equals(id) && in.getIndex() == index),
                "tagged with an input the body spends: " + id + "#" + index);
        assertTrue(f.toString().contains(id), "the recorded call's input");
    }

    private static byte[] bytesIn(com.bloxbean.cardano.client.plutus.spec.PlutusData d) {
        if (d instanceof com.bloxbean.cardano.client.plutus.spec.BytesPlutusData b) return b.getValue();
        var c = (com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData) d;
        assertEquals(1, c.getData().getPlutusDataList().size());
        return bytesIn(c.getData().getPlutusDataList().get(0));
    }

    // ------------------------------------------------------------------ the call scope (D7)

    @Test
    void aScopedPrepareTakesItsBoundsFromTheScope() throws Exception {
        JsonNode f = fixture("0003-composed_transaction.json");
        Snapshot s = Fixtures.snapshot(f);
        long now = s.tip.getSlot() + 100;
        s.tip = null;   // a latest-block read would now throw Need
        var rt = runtime(new SnapshotBackend(s), b -> b);
        var p = rt.inScope(CallScope.at(now).ttlSlots(30), () -> prepare(rt, f.get("call")));
        assertEquals(now + 30, p.validToSlot().orElseThrow());
        assertNull(rt.callScope());
    }

    // ------------------------------------------------------------------ the claim store (D8)

    /** A store that records what the runtime asks of it and avoids a given set. */
    static final class RecordingStore implements ClaimStore {
        final Set<String> designated = ConcurrentHashMap.newKeySet();
        final Set<Claim> claims = ConcurrentHashMap.newKeySet();
        final Map<Claim, Expiry> expiries = new ConcurrentHashMap<>();
        final List<Set<String>> added = new ArrayList<>();
        final Set<String> avoid = new HashSet<>();
        String retryRef;

        public boolean designate(String ref) { return designated.add(ref); }
        public boolean isDesignated(String ref) { return designated.contains(ref); }
        public void undesignate(String ref, Expiry when) { designated.remove(ref); }
        public synchronized void add(Claim claim, Expiry when) {
            claims.add(claim);
            added.add(Set.copyOf(claim.refs()));
        }
        public void remove(Claim claim) { claims.remove(claim); }
        public void expire(Claim claim, Expiry when) { expiries.put(claim, when); }
        public Collection<Claim> claims() { return claims; }
        public boolean avoids(String ref) { return avoid.contains(ref); }
        public boolean reusable(String ref) { return ref.equals(retryRef); }
    }

    @Test
    void aClaimStoreSetThroughTheBuilderSeesThePreparesClaim_withTheBodysSlot() throws Exception {
        JsonNode f = fixture("0003-composed_transaction.json");
        var store = new RecordingStore();
        var rt = runtime(new SnapshotBackend(Fixtures.snapshot(f)), b -> b.claimStore(store));
        var p = prepare(rt, f.get("call"));
        assertEquals(f.get("txHash").asText(), p.txHash(), "a store that avoids nothing changes nothing");
        Transaction body = Transaction.deserialize(HexUtil.decodeHexString(p.cbor()));
        var spent = new HashSet<String>();
        body.getBody().getInputs().forEach(i -> spent.add(i.getTransactionId() + "#" + i.getIndex()));
        if (body.getBody().getCollateral() != null) body.getBody().getCollateral().forEach(i -> spent.add(i.getTransactionId() + "#" + i.getIndex()));
        assertTrue(store.added.stream().anyMatch(spent::equals), "the claim holds the inputs and collateral: " + store.added);
        var claim = store.claims.stream().filter(c -> c.refs().equals(spent)).findFirst().orElseThrow();
        assertEquals(body.getBody().getTtl(), store.expiries.get(claim).untilSlot(), "released by the chain's slot, the body's TTL");
    }

    @Test
    void anAvoidedInputIsNeverSelected() throws Exception {
        JsonNode f = fixture("0003-composed_transaction.json");
        String wallet = f.get("call").get("address").asText();
        Snapshot s = Fixtures.snapshot(f);
        // The wallet's one UTxO, and a second like it (another reference), so selection has a choice.
        var mine = s.utxos.get(wallet);
        assertEquals(1, mine.size(), "the recorded wallet holds one UTxO");
        var original = mine.get(0);
        var twin = JSON.readValue(JSON.writeValueAsString(original), com.bloxbean.cardano.client.api.model.Utxo.class);
        twin.setTxHash("ee".repeat(32));
        var both = new ArrayList<>(mine);
        both.add(twin);
        s.utxos.put(wallet, both);
        s.outputs.put(Snapshot.ref(twin.getTxHash(), twin.getOutputIndex()), twin);
        String avoided = original.getTxHash() + "#" + original.getOutputIndex();

        var plain = prepare(runtime(new SnapshotBackend(s), b -> b), f.get("call"));
        assertTrue(refs(plain).contains(avoided), "without the store's avoid set, the original is selected");

        var store = new RecordingStore();
        store.avoid.add(avoided);
        var p = prepare(runtime(new SnapshotBackend(s), b -> b.claimStore(store)), f.get("call"));
        assertFalse(refs(p).contains(avoided), "an avoided input is neither spent nor collateral: " + refs(p));
        assertTrue(refs(p).contains("ee".repeat(32) + "#" + twin.getOutputIndex()));

        // Alone, the avoided input is never taken: the build fails instead.
        var alone = Fixtures.snapshot(f);
        var store2 = new RecordingStore();
        store2.avoid.add(avoided);
        var rt = runtime(new SnapshotBackend(alone), b -> b.claimStore(store2));
        assertThrows(RuntimeException.class, () -> prepare(rt, f.get("call")));
    }

    @Test
    void reservationsOnInstanceAndOwnedObjectInputsRefuseThePrepare() throws Exception {
        for (String fixture : List.of("0003-composed_transaction.json", firstCall("Bank", "deposit"))) {
            JsonNode f = fixture(fixture);
            var call = f.get("call");
            Snapshot snapshot = Fixtures.snapshot(f);
            var body = Transaction.deserialize(HexUtil.decodeHexString(f.get("unsignedTx").asText())).getBody();
            var ref = body.getInputs().stream().map(i -> Snapshot.ref(i.getTransactionId(), i.getIndex()))
                    .filter(r -> !snapshot.outputs.get(r).getAddress().equals(call.get("address").asText()))
                    .findFirst().orElseThrow();
            var store = new RecordingStore();
            store.avoid.add(ref);
            var rt = runtime(new SnapshotBackend(snapshot), b -> b.claimStore(store));
            assertEquals(ref, assertThrows(ReservedInputException.class, () -> prepare(rt, call)).ref());
            assertTrue(store.claims.isEmpty());
        }
    }

    @Test
    void suppliedDesignationCanReuseOnlyItsOwnReservation() throws Exception {
        var f = fixture("0003-composed_transaction.json");
        var snapshot = Fixtures.snapshot(f);
        JsonNode call = f.get("call");
        String wallet = call.get("address").asText();
        var input = snapshot.utxos.get(wallet).getFirst();
        var alternative = JSON.treeToValue(JSON.valueToTree(input), Utxo.class);
        alternative.setTxHash("ff".repeat(32));
        snapshot.utxos.put(wallet, List.of(input, alternative));
        snapshot.outputs.put(Snapshot.ref(alternative.getTxHash(), alternative.getOutputIndex()), alternative);
        var ref = Snapshot.ref(input.getTxHash(), input.getOutputIndex());
        var store = new RecordingStore();
        store.avoid.add(ref);
        var rt = runtime(new SnapshotBackend(snapshot), b -> b.claimStore(store));
        assertEquals(ref, assertThrows(ReservedInputException.class, () -> prepare(rt, call, ref)).ref());
        store.retryRef = ref;
        assertTrue(refs(prepare(rt, call, ref)).contains(ref));
        snapshot.utxos.put(wallet, List.of(input));
        assertTrue(refs(prepare(rt, call, ref)).contains(ref), "the retry can also use its input for collateral");
    }

    @Test
    void creationAndActionSelectTheSameInputsAsTheEligibleWalletInProviderOrder() throws Exception {
        for (String file : List.of("0002-composed_transaction.json", "0003-composed_transaction.json")) {
            var f = fixture(file);
            var call = f.get("call");
            String wallet = call.get("address").asText();
            var snapshot = Fixtures.snapshot(f);
            var original = snapshot.utxos.get(wallet).getFirst();
            var inputs = new ArrayList<Utxo>();
            var eligible = new ArrayList<Utxo>();
            var store = new RecordingStore();
            for (int i = 0; i < 130; i++) {
                var input = JSON.treeToValue(JSON.valueToTree(original), Utxo.class);
                input.setTxHash("%064x".formatted(i + 1));
                String ref = Snapshot.ref(input.getTxHash(), input.getOutputIndex());
                inputs.add(input);
                snapshot.outputs.put(ref, input);
                if (i < 100 || i % 2 == 0) store.avoid.add(ref);
                else eligible.add(input);
            }
            snapshot.utxos.put(wallet, eligible);
            String expected = prepare(runtime(new SnapshotBackend(snapshot), b -> b), call).cbor();
            snapshot.utxos.put(wallet, inputs);
            var rt = runtime(new SnapshotBackend(snapshot), b -> b.claimStore(store));
            assertEquals(expected, prepare(rt, call).cbor(), file);
        }
    }

    private static final ReadFailed ABORT = new ReadFailed("output", "ab#0", "timeout");

    record Input(long n) {
        Input { throw ABORT; }
    }

    record Output(long n) {
        public long n() { throw ABORT; }
    }

    @Test
    void reflectedRecordFailuresKeepTheirIdentityInBothDirections() {
        assertSame(ABORT, assertThrows(ReadFailed.class,
                () -> JsonValues.fromJson(JSON.createObjectNode().put("n", 1), Input.class, "arg")));
        assertSame(ABORT, assertThrows(ReadFailed.class, () -> JsonValues.toJson(List.of(new Output(1)))));
    }

    /** The inputs and collateral of a prepared transaction's body. */
    static Set<String> refs(PreparedTx p) throws Exception {
        Transaction t = Transaction.deserialize(HexUtil.decodeHexString(p.cbor()));
        var out = new HashSet<String>();
        t.getBody().getInputs().forEach(i -> out.add(i.getTransactionId() + "#" + i.getIndex()));
        if (t.getBody().getCollateral() != null) t.getBody().getCollateral().forEach(i -> out.add(i.getTransactionId() + "#" + i.getIndex()));
        return out;
    }
}
