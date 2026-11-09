package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.MerkleMap;
import org.julclang.state.api.MerkleMaps;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.token.Token;
import org.julclang.stdlib.lib.ByteStringLib;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.toolchain.TransitionParityTest.*;

/**
 * ADR-0015 S2b: MerkleMap read discovery (D3) on {@code examples/token}. A transition program runs in rounds: each
 * key it reads outside the map's proven reads comes back in a {@code joust:read} trace (answered as absent meanwhile);
 * the driver proves them (present or absent) and runs again, until a run reads nothing new. The final run's result
 * and the read set equal the JVM's (which reads by callback), refusals included, within the round limit.
 */
class ReadDiscoveryTest {

    static final int ROUND_LIMIT = 4;
    static final HexFormat HEX = HexFormat.of();
    /** Any root but the empty map's: every lookup outside the reads needs a proof. */
    static final byte[] ROOT = new byte[32];

    static {
        java.util.Arrays.fill(ROOT, (byte) 0x11);
    }

    /** One evaluation: "ok:<hex>" or "refused", and the keys it asked for (hex), in order. */
    record Run(String result, List<String> reads) {}

    static Run run(String program, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        var in = new ArrayList<org.julclang.core.PlutusData>();
        for (PlutusData d : List.of(target, shared, args, ctx)) {
            try {
                in.add(PlutusDataCborDecoder.decode(d.serializeToBytes()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        EvalResult r = VM.evaluateWithArgs(JulcScriptAdapter.toProgram(program),
                org.julclang.vm.LedgerEvaluationTarget.pv11(org.julclang.vm.PlutusLanguage.PLUTUS_V3), in,
                new org.julclang.vm.ExBudget(10_000_000_000L, 14_000_000L), org.julclang.vm.EvalOptions.DEFAULT);
        List<String> traces = switch (r) {
            case EvalResult.Success s -> s.traces();
            case EvalResult.Failure f -> f.traces();
            case EvalResult.BudgetExhausted b -> b.traces();
        };
        var reads = new ArrayList<String>();
        for (String t : traces) {
            if (t.startsWith("joust:read ")) {
                String[] p = t.split(" ");
                assertEquals(HEX.formatHex(ROOT), p[1], "the map's root in the trace");
                reads.add(p.length > 2 ? p[2] : "");
            }
        }
        String result = r instanceof EvalResult.Success s
                ? "ok:" + HEX.formatHex(PlutusDataCborEncoder.encode(org.julclang.vm.TermExtractor.extractData(s.resultTerm())))
                : "refused";
        return new Run(result, reads);
    }

    /** The driver's outcome: the final result, the proven reads in discovery order, and the rounds it took. */
    record Discovered(String result, List<MapEntry> reads, int rounds) {}

    /** Runs {@code program} in rounds over {@code backing} (the map's contents: key hex → value). */
    static Discovered discover(String program, Function<JulcList<MapEntry>, Token.State> target, PlutusData args,
                               PlutusData ctx, Map<String, byte[]> backing) {
        var proven = new ArrayList<MapEntry>();
        for (int round = 1; round <= ROUND_LIMIT; round++) {
            var reads = JulcList.<MapEntry>empty();
            for (int i = proven.size() - 1; i >= 0; i--) reads = reads.prepend(proven.get(i));
            Run r = run(program, data(target.apply(reads)), NONE, args, ctx);
            boolean fresh = false;
            for (String k : r.reads()) {
                if (proven.stream().anyMatch(e -> HEX.formatHex(e.key()).equals(k))) continue;
                byte[] v = backing.get(k);
                proven.add(new MapEntry(HEX.parseHex(k), v != null, v != null ? v : new byte[0]));
                fresh = true;
            }
            if (!fresh) return new Discovered(r.result(), proven, round);
        }
        return new Discovered("over the round limit", proven, ROUND_LIMIT + 1);
    }

    /** The JVM: the method with reads by callback (recorded), and its result as the runtime encodes it. */
    record Jvm(String result, List<String> reads) {}

    static Jvm jvm(java.util.function.Supplier<Token.State> call, Map<String, byte[]> backing) {
        var reads = new ArrayList<String>();
        MerkleMaps.ReadSource source = (root, key) -> {
            String k = HEX.formatHex(key);
            reads.add(k);
            byte[] v = backing.get(k);
            return new MapEntry(key, v != null, v != null ? v : new byte[0]);
        };
        String result;
        try {
            result = "ok:" + result(MerkleMaps.withReads(source, call::get), null).toLowerCase();
        } catch (RequireException e) {
            result = "refused";
        }
        return new Jvm(result, reads);
    }

    static byte[] amount(long v) {
        return ByteStringLib.integerToByteString(true, 0, BigInteger.valueOf(v));
    }

    static byte[] pkh(int seed) {
        byte[] b = new byte[28];
        java.util.Arrays.fill(b, (byte) seed);
        return b;
    }

    @Test
    void tokenReadsAreDiscoveredInRoundsAndMatchTheJvm() throws Exception {
        var t = transitions("token");
        assertTrue(t.keySet().containsAll(List.of("mint", "transfer", "burn")), t.keySet().toString());

        byte[] admin = pkh(1), ann = pkh(2), ben = pkh(3), cy = pkh(4);
        var backing = new LinkedHashMap<String, byte[]>();
        backing.put(HEX.formatHex(ann), amount(1_000));
        backing.put(HEX.formatHex(ben), amount(500));
        BigInteger supply = BigInteger.valueOf(1_500);
        Function<JulcList<MapEntry>, Token.State> state = reads ->
                new Token.State(admin, supply, new MerkleMap(ROOT, reads, JulcList.empty()));

        record Case(String name, String method, PlutusData args, byte[] caller, java.util.function.Function<Token.State, Token.State> jvm) {}
        var cases = new ArrayList<Case>();
        for (byte[] to : List.of(ann, cy)) {
            for (long n : List.of(1L, 0L)) {
                cases.add(new Case("mint " + n + " to " + to[0], "mint", args(to, BigInteger.valueOf(n)), admin,
                        s -> Token.mint(s, to, BigInteger.valueOf(n))));
            }
        }
        for (byte[] from : List.of(ann, ben, cy)) {
            for (byte[] to : List.of(ben, cy, from)) {
                for (long n : List.of(250L, 500L, 2_000L)) {
                    cases.add(new Case("transfer " + n + " " + from[0] + "->" + to[0], "transfer", args(to, BigInteger.valueOf(n)), from,
                            s -> Token.transfer(s, to, BigInteger.valueOf(n), ctx(from))));
                }
            }
            for (long n : List.of(100L, 500L, 1_000L, 5_000L)) {
                cases.add(new Case("burn " + n + " by " + from[0], "burn", args(BigInteger.valueOf(n)), from,
                        s -> Token.burn(s, BigInteger.valueOf(n), ctx(from))));
            }
        }

        int maxRounds = 0, refused = 0;
        var vectors = new ArrayList<Map<String, Object>>();
        for (Case c : cases) {
            Discovered d = discover(t.get(c.method()), state, c.args(), data(ctx(c.caller())), backing);
            assertTrue(d.rounds() <= ROUND_LIMIT, c.name() + ": rounds " + d.rounds());
            // The read set: the JVM's (with no reads given, by callback) equals the discovered one, in order.
            Jvm discoveredByJvm = jvm(() -> c.jvm().apply(state.apply(JulcList.empty())), backing);
            assertEquals(discoveredByJvm.reads(), d.reads().stream().map(e -> HEX.formatHex(e.key())).toList(), c.name() + ": read set");
            // The result: the JVM's, given the same proven reads, byte for byte (or both refuse).
            var given = JulcList.<MapEntry>empty();
            for (int i = d.reads().size() - 1; i >= 0; i--) given = given.prepend(d.reads().get(i));
            var finalState = state.apply(given);
            Jvm jvmResult = jvm(() -> c.jvm().apply(finalState), backing);
            assertEquals(jvmResult.result(), d.result(), c.name() + ": result");
            assertEquals(List.of(), jvmResult.reads(), c.name() + ": the final run reads only proven keys");
            maxRounds = Math.max(maxRounds, d.rounds());
            if (d.result().equals("refused")) refused++;
            var v = new LinkedHashMap<String, Object>();
            v.put("name", c.name());
            v.put("program", t.get(c.method()));
            v.put("args", List.of(data(finalState), NONE, c.args(), data(ctx(c.caller()))).stream().map(PlutusData::serializeToHex).toList());
            v.put("expected", d.result());
            v.put("rounds", d.rounds());
            // Round 1 (no proven reads): the keys it reports, for the same check in Wasm.
            var first = List.of(data(state.apply(JulcList.empty())), NONE, c.args(), data(ctx(c.caller())));
            v.put("round1Args", first.stream().map(PlutusData::serializeToHex).toList());
            v.put("round1Reads", run(t.get(c.method()), first.get(0), first.get(1), first.get(2), first.get(3)).reads());
            vectors.add(v);
        }
        assertTrue(refused > 0 && refused < cases.size(), "accepted and refused calls");
        assertTrue(maxRounds >= 3, "a read that depends on an earlier one takes another round: " + maxRounds);
        System.out.println("[S2b] " + cases.size() + " token calls: read sets and results identical to the JVM (" + refused
                + " refusals), at most " + maxRounds + " rounds (limit " + ROUND_LIMIT + ")");
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/s2b-vectors.json"), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(vectors));
    }

    static Ctx ctx(byte[] caller) {
        return new Ctx(caller, BigInteger.ZERO, BigInteger.valueOf(2_000_000), BigInteger.ZERO, BigInteger.ZERO);
    }
}
