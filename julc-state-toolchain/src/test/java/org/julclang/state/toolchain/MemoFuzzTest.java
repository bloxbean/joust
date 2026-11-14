package org.julclang.state.toolchain;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.julclang.core.PlutusData;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.DepositV;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;

import static org.julclang.state.toolchain.JscV2M1aFourWayTest.at;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-guided fuzzing of the generated memo decoders (ADR-0018 D5, M2c: a {@code receives T} action's deposits, each
 * memo a checked input decoded on the data). {@code Inbox.jsc}'s receivers take a Slip (a record of a Bool, an Option, an
 * enum, a List, a Map, a Value and an Address: every decoder branch), a Key, a Bytes and an unread Value; each input is
 * a memo that is mostly the honest one with a part changed (another tag, a field more or less, a value of another kind,
 * a hash or key of the wrong length, a value at the edges), sometimes any data. The same memo is handed to the generated
 * Java on the JVM ({@link FourWay#jvmRaw}) and to the UPLC transition program ({@link FourWay#receiverRaw}): the JVM body
 * and the on-chain body must agree, refusing or giving the same step. When the transition program refuses, the validator,
 * given the same memo in the registry, must refuse too.
 * <p>
 * A JVM body may refuse with {@code RequireException} (the decoder's {@code #memo:} rule) or an arithmetic failure (as
 * {@link FourWay} reads a refusal). An exception of another kind thrown where the transition program refuses is a
 * registered divergence: the generated decoders call {@code Builtins.unIData}, {@code unBData}, {@code constrTag} and
 * {@code unListData} on the memo before checking its kind, which on the JVM throw IllegalArgumentException, not a refusal
 * with the {@code #memo:} key ({@link #KNOWN_ISSUE}). It is counted by exception and builtin, and its first input written
 * to {@code build/fuzz/}. Anything else fails the input.
 */
class MemoFuzzTest {

    static final FuzzData.Tally TALLY = new FuzzData.Tally("MemoFuzzTest");
    static final String KNOWN_ISSUE = "#96, the JVM's memo decoders throw IllegalArgumentException on data of another kind";
    static FourWay inbox;
    static Tx slipTx, keyedTx;
    static PlutusData slip;

    @BeforeAll
    static void build() throws Exception {
        JscV2M2cFourWayTest.build();
        inbox = JscV2M2cFourWayTest.LEGS.get("Inbox");
        slipTx = JscV2M2cFourWayTest.deposits(inbox, at(JscV2M2cFourWayTest.BUYER, 0, 0),
                new DepositV(JscV2M2cFourWayTest.lovelace(1_000_000), JscV2M2cFourWayTest.slipB()));
        keyedTx = JscV2M2cFourWayTest.deposits(inbox, at(JscV2M2cFourWayTest.BUYER, 0, 0),
                new DepositV(JscV2M2cFourWayTest.lovelace(1_000_000), Bytes.of(JscV2M2cFourWayTest.A)));
        slip = inbox.memoData(JscV2M2cFourWayTest.slipB());
    }

    @AfterAll
    static void report() {
        TALLY.write();
    }

    /** {@code d} with one part changed, at most {@code depth} levels down. */
    static PlutusData mutate(FuzzedDataProvider f, PlutusData d, int depth) {
        if (depth == 0 || f.consumeInt(0, 3) == 0) {
            return switch (f.consumeInt(0, 5)) {
                case 0 -> FuzzData.any(f, 2);
                case 1 -> d instanceof PlutusData.ConstrData c ? new PlutusData.ConstrData(f.consumeInt(0, 6), c.fields()) : FuzzData.any(f, 1);
                case 2 -> d instanceof PlutusData.BytesData ? PlutusData.bytes(FuzzData.bytes(f)) : FuzzData.any(f, 1);
                case 3 -> d instanceof PlutusData.IntData ? PlutusData.integer(FuzzData.integer(f)) : FuzzData.any(f, 1);
                case 4 -> FuzzData.valueLike(f);
                default -> d;
            };
        }
        return switch (d) {
            case PlutusData.ConstrData c -> {
                var fs = new ArrayList<>(c.fields());
                int op = f.consumeInt(0, 4);
                if (fs.isEmpty() || op == 0) fs.add(FuzzData.any(f, 1));
                else if (op == 1) fs.remove(f.consumeInt(0, fs.size() - 1));
                else {
                    int i = f.consumeInt(0, fs.size() - 1);
                    fs.set(i, mutate(f, fs.get(i), depth - 1));
                }
                yield new PlutusData.ConstrData(c.tag(), fs);
            }
            case PlutusData.ListData l -> {
                var xs = new ArrayList<>(l.items());
                if (xs.isEmpty() || f.consumeBoolean()) xs.add(FuzzData.any(f, 1));
                else {
                    int i = f.consumeInt(0, xs.size() - 1);
                    xs.set(i, mutate(f, xs.get(i), depth - 1));
                }
                yield new PlutusData.ListData(xs);
            }
            case PlutusData.MapData m -> {
                var ps = new ArrayList<>(m.entries());
                if (ps.isEmpty() || f.consumeBoolean()) ps.add(new PlutusData.Pair(PlutusData.bytes(FuzzData.bytes(f)), FuzzData.any(f, 1)));
                else {
                    int i = f.consumeInt(0, ps.size() - 1);
                    var p = ps.get(i);
                    ps.set(i, f.consumeBoolean() ? new PlutusData.Pair(mutate(f, p.key(), depth - 1), p.value())
                            : new PlutusData.Pair(p.key(), mutate(f, p.value(), depth - 1)));
                }
                yield new PlutusData.MapData(ps);
            }
            default -> mutate(f, d, 0);
        };
    }

    /** The memo as the registry holds it: canonical CBOR, as cardano-client-lib writes the datum (map keys sorted, one of each). */
    static PlutusData canonical(PlutusData d) {
        try {
            var ccl = org.julclang.clientlib.PlutusDataAdapter.toClientLib(d);
            var back = com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(com.bloxbean.cardano.client.util.HexUtil.decodeHexString(ccl.serializeToHex()));
            return org.julclang.clientlib.PlutusDataAdapter.fromClientLib(back);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The next state a receiving call with one deposit proposes whatever its memo holds: keyed counts it in keys, named and
     * valued in names (state fields 8 and 9); post's depends on the slip, and the honest slip's is taken.
     */
    static Rec candidate(String method, Rec state) {
        if (method.equals("post")) return inbox.next("post", state, List.of(), inbox.itx(slipTx, JscV2M2cFourWayTest.BUYER));
        var fs = new ArrayList<Object>(state.fields());
        int k = method.equals("keyed") ? 8 : 9;
        fs.set(k, ((java.math.BigInteger) fs.get(k)).add(java.math.BigInteger.ONE));
        return new Rec(state.type(), fs);
    }

    // ------------------------------------------------------------------ the oracle's own controls (review r1, Codex C1)

    static JscGen.Plan keyBox(String name, String memoType) {
        String src = "jsc 2\npackage " + JscGen.PKG + "\n\ncontract " + name + "(maxDatumBytes = 512) {\n"
                + "  state { owner: Key, keys: Int }\n  init() { return { owner: caller, keys: 0 } }\n"
                + "  action keyed() receives " + memoType + " { return { ...state, keys: keys + funds.deposits.length } }\n}\n";
        var files = new java.util.LinkedHashMap<String, String>();
        files.put(JscGen.DIR + name + ".jsc", src);
        return new JscGen.Plan(name, files, JscGen.DIR + name + ".jsc", null, List.of(), List.of("memo oracle control"),
                java.util.Map.of("init", "init", "keyed", "action"));
    }

    /**
     * The oracle refuses a validator whose decoder alone is wrong: KeyBox's keyed() receives a Key (28 bytes, checked); its
     * mutant's validator receives Bytes. With KeyBox's transition program, a 27-byte memo is refused by the transition
     * program; KeyBox's validator refuses it too, given the call's own next state, but the mutant's accepts it, which is
     * exactly what decodersAgree asserts against. The honest 28-byte memo is accepted by both (the positive control).
     */
    @org.junit.jupiter.api.Test
    void theOracle_catchesAValidatorOnlyDecoderMutant() throws Exception {
        crypto();
        var honest = GeneratedContracts.build(keyBox("KeyBox", "Key")).legs();
        var built = GeneratedContracts.build(keyBox("KeyBoxMutant", "Bytes"));
        var mutant = new FourWay(JscGen.PKG, built.legs().loader, "KeyBoxMutant", built.plan().source(), built.plan().contractFile(),
                org.julclang.state.dsl.v2.JscModules.of(built.plan().files()), org.julclang.state.dsl.v2.JavaLibraries.NONE,
                honest.transitions, null);
        byte[] buyer = JscV2M2cFourWayTest.BUYER;
        Rec state = new Rec("State", List.of(Bytes.of(JscGen.OWNER), java.math.BigInteger.ZERO));
        Rec next = new Rec("State", List.of(Bytes.of(JscGen.OWNER), java.math.BigInteger.ONE));
        for (FourWay f : List.of(honest, mutant)) {
            Tx t = JscV2M2cFourWayTest.deposits(f, at(buyer, 0, 0), new DepositV(JscV2M2cFourWayTest.lovelace(1_000_000), Bytes.of(JscV2M2cFourWayTest.A)));
            Object[] ok = f.receiverRaw("keyed", state, t, List.of(PlutusData.bytes(JscV2M2cFourWayTest.A)), next, buyer);
            assertTrue(((String) ok[0]).startsWith("ok:"), f.name + ": the honest memo, the transition program");
            assertEquals(true, ok[1], f.name + ": the honest memo, the validator " + f.lastVerdict);
            Object[] bad = f.receiverRaw("keyed", state, t, List.of(PlutusData.bytes(new byte[27])), next, buyer);
            assertEquals("refused", bad[0], f.name + ": a 27-byte Key, the transition program");
            assertEquals(f == mutant, bad[1], f.name + ": a 27-byte Key, the validator " + f.lastVerdict);
        }
    }

    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    @FuzzTest(maxDuration = "30s")
    void decodersAgree(FuzzedDataProvider data) throws Exception {
        TALLY.input();
        String method;
        Tx t;
        PlutusData memo;
        switch (data.consumeInt(0, 3)) {
            case 0 -> {
                method = "keyed";
                t = keyedTx;
                memo = mutate(data, PlutusData.bytes(JscV2M2cFourWayTest.A), 1);
            }
            case 1 -> {
                method = data.consumeBoolean() ? "named" : "valued";
                t = keyedTx;
                memo = data.consumeBoolean() ? FuzzData.valueLike(data) : FuzzData.any(data, 3);
            }
            default -> {
                method = "post";
                t = slipTx;
                memo = data.consumeInt(0, 9) == 0 ? FuzzData.any(data, 4) : mutate(data, slip, 4);
            }
        }
        // The registry writes the memo as canonical CBOR, so both legs get what the chain would hold (an order or a
        // repetition attack on a map cannot be carried; ValueAdapterFuzzTest and the Map decoder's own tests cover them).
        memo = canonical(memo);
        Rec state = JscV2M2cFourWayTest.INBOX;
        String jvm = inbox.jvmRaw(method, state, t, List.of(memo));
        Object jvmNext = jvm.startsWith("ok:") ? FourWay.nextStateOf(inbox.lastJvmResult) : null;
        RuntimeException thrown = inbox.lastJvmThrow;
        // The validator is given the state the call would really produce (review r1, Codex C1): for keyed, named and
        // valued it does not depend on the memo's contents (a count per deposit), so the honest call's next state is the
        // candidate a permissive decoder would also propose; for post it does, and the candidate is the honest slip's.
        Object candidate = inbox.java(candidate(method, state));
        Object[] chain = inbox.receiverRawJava(method, state, t, List.of(memo), jvmNext != null ? jvmNext : candidate,
                JscV2M2cFourWayTest.BUYER);
        String tp = (String) chain[0];
        boolean validator = (Boolean) chain[1];
        String what = method + " with the memo " + memo + "\n  JVM: " + jvm + "\n  transition program: " + tp + "\n  validator: " + validator;
        String builtin = FuzzData.builtinWrongKind(thrown);
        if (builtin != null && tp.equals("refused") && !validator) {
            // #96, exactly: an un*Data builtin of the JVM's Builtins, given data of another kind, throws where the chain
            // refuses (review r1, Codex C5, security S3). Any other exception fails the input.
            TALLY.count("known divergence (" + KNOWN_ISSUE + "): " + builtin);
            var p = java.nio.file.Path.of("build/fuzz/MemoFuzzTest.known-" + builtin + ".txt");
            if (!java.nio.file.Files.exists(p)) {
                java.nio.file.Files.createDirectories(p.getParent());
                java.nio.file.Files.writeString(p, what + "\n");
            }
            return;
        }
        TALLY.count(method + ": " + (tp.equals("refused") ? "refused" : "accepted"));
        try {
            assertEquals(tp, jvm, "the JVM body and the transition program disagree on a memo");
            if (tp.equals("refused")) {
                assertFalse(validator, "the transition program refuses a memo the validator accepts with the call's own next state");
                TALLY.count(method + ": refused by the validator too");
            } else {
                // Liveness (review r1, design F11): a memo the transition program accepts, with the JVM's next state (the
                // same as the transition program's), is accepted by the validator.
                assertTrue(validator, "the validator refuses a memo the transition program and the JVM accept " + inbox.lastVerdict);
                TALLY.count(method + ": accepted by the validator too");
            }
        } catch (AssertionError e) {
            var p = FuzzData.reproducer("MemoFuzzTest", what + "\n" + e.getMessage() + "\n");
            throw new AssertionError(e.getMessage() + " (" + p + "):\n" + what, e);
        }
    }
}
