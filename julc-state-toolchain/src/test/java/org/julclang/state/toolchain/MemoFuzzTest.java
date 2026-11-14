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
    static final String KNOWN_ISSUE = "the JVM's memo decoders throw IllegalArgumentException on data of another kind";
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
        Object[] chain = inbox.receiverRaw(method, state, t, List.of(memo), null, JscV2M2cFourWayTest.BUYER);
        String tp = (String) chain[0];
        boolean validator = (Boolean) chain[1];
        String what = method + " with the memo " + memo + "\n  JVM: " + jvm + "\n  transition program: " + tp + "\n  validator: " + validator;
        if (jvm.startsWith("threw: ") && tp.equals("refused") && !validator && jvm.contains("IllegalArgumentException")) {
            String cls = jvm.replaceAll("^threw: ", "").replaceAll(":.*", "") + " (" + jvm.replaceAll("(?s).*?(un[A-Za-z]+Data|constrTag|constrFields|headList|tailList|fstPair|sndPair).*", "$1") + ")";
            TALLY.count("known divergence (" + KNOWN_ISSUE + "): " + (cls.length() > 120 ? cls.substring(0, 120) : cls));
            var p = java.nio.file.Path.of("build/fuzz/MemoFuzzTest.known-" + Integer.toHexString(cls.hashCode()) + ".txt");
            if (!java.nio.file.Files.exists(p)) {
                java.nio.file.Files.createDirectories(p.getParent());
                java.nio.file.Files.writeString(p, what + "\n");
            }
            return;
        }
        TALLY.count(method + ": " + (tp.equals("refused") ? "refused" : "accepted"));
        try {
            assertEquals(tp, jvm, "the JVM body and the transition program disagree on a memo");
            if (tp.equals("refused")) assertFalse(validator, "the transition program refuses a memo the validator accepts");
        } catch (AssertionError e) {
            var p = FuzzData.reproducer("MemoFuzzTest", what + "\n" + e.getMessage() + "\n");
            throw new AssertionError(e.getMessage() + " (" + p + "):\n" + what, e);
        }
    }
}
