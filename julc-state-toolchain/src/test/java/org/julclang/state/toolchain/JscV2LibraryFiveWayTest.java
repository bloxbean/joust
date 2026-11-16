package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.JscModules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.FourWay.*;
import static org.julclang.state.toolchain.TransitionParityTest.result;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M4 (D9, J3): library calls on all five legs. {@code Shop.jsc} imports the {@code .jsc} library
 * {@code fees.jsc} (its record and enum in the state and the redeemers; its fns in requires, values, payouts, a match and
 * a recursion; a library {@code require} with its own message). Every call runs on both reference interpreters (the
 * JavaScript one evaluates the library's fns from the IR's {@code libraries}), the JVM (the generated {@code Shop}
 * calling the generated {@code Fees} class), the transition program and the validator (JuLC compiles the library's
 * bodies into both scripts), and all agree. Some outcomes are also stated by hand.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2LibraryFiveWayTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");
    static final String DIR = "org/julclang/state/examples/dsl/";
    static final BigInteger T0 = BigInteger.valueOf(1_800_000_000_000L);
    static final BigInteger WINDOW = BigInteger.valueOf(120_000);
    static final byte[] OWNER = fill(0x0A), ANN = fill(0xA1), BEN = fill(0xB1);
    static FourWay shop;
    static Map<String, String> files;

    static byte[] fill(int b) {
        byte[] x = new byte[28];
        Arrays.fill(x, (byte) b);
        return x;
    }

    static BigInteger n(long v) {
        return BigInteger.valueOf(v);
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        files = new LinkedHashMap<>();
        for (String f : List.of("fees.jsc", "Shop.jsc")) files.put(DIR + f, Files.readString(DSL.resolve(f)));
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        assertEquals(List.of("Shop"), r.contracts().stream().map(Toolchain.Contract::name).toList(), "a library is no contract");
        // The library's own class, lowered on the npm path as on Gradle.
        assertTrue(r.generated().stream().anyMatch(g -> g.javaFile().equals(DIR + "Fees.java") && g.abiJson() == null && g.irJson() == null),
                "fees.jsc lowers to the @OnchainLibrary Fees, without a manifest or an IR of its own");
        var c = r.contracts().get(0);
        shop = new FourWay("Shop", files.get(DIR + "Shop.jsc"), DIR + "Shop.jsc", JscModules.of(files), c.transitions());
    }

    static Tx at(byte[] caller, long received, long balance) {
        return tx(caller, received, balance, T0, T0.add(WINDOW));
    }

    static Rec line(byte[] to, long bps) {
        return rec("Line", b(to), n(bps));
    }

    static Rec state(long price, List<Object> lines, Rec tier, long sold) {
        return rec("State", b(OWNER), n(price), lines, tier, n(sold), Bytes.of(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
    }

    static List<Rec> tiers() {
        return List.of(rec("Basic"), rec("Gold", n(0)), rec("Gold", n(9)), rec("Gold", n(10)), rec("Gold", n(-3)));
    }

    @Test
    @Order(1)
    void init_libraryTypesAsCheckedInputs_andALibraryRequire() throws Exception {
        byte[] label = {9, 8, 7};
        for (long price : List.of(0L, 10_000_000L, -1L)) {
            for (List<Object> lines : List.<List<Object>>of(List.of(), List.of(line(ANN, 6_000), line(BEN, 4_000)),
                    List.of(line(ANN, 6_000), line(BEN, 4_001)), List.of(rec("Line", Bytes.of(new byte[27]), n(1))))) {
                shop.check("init", null, List.of(n(price), lines, b(label)), at(OWNER, 0, 0), OWNER);
            }
        }
        // By hand: the library's require refuses a negative price; the tag is the library's slice(blake2b_224(label), 0, 8).
        assertEquals("refused", shop.check("init", null, List.of(n(-1), List.of(), b(label)), at(OWNER, 0, 0), OWNER));
        assertEquals("require:32:3:an amount is never negative", shop.lastRefusal,
                "the library's own require (fees.jsc:32:3), its message as written");
        String ok = shop.check("init", null, List.of(n(5), List.of(line(ANN, 10_000)), b(label)), at(OWNER, 0, 0), OWNER);
        var lines = (org.julclang.core.types.JulcList<org.julclang.state.examples.dsl.Fees.Line>) shop.java(List.of(line(ANN, 10_000)));
        var expected = new org.julclang.state.examples.dsl.Shop.State(OWNER, n(5), lines, new org.julclang.state.examples.dsl.Fees.Basic(),
                n(0), Arrays.copyOf(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(label), 8));
        assertEquals("ok:" + result(expected, null), ok, "by hand: the tag from cardano-client-lib's blake2b-224");
        assertEquals("refused", shop.check("init", null, List.of(n(5), List.of(rec("Line", Bytes.of(new byte[27]), n(1))), b(label)),
                at(OWNER, 0, 0), OWNER), "a library record's Key is checked at the boundary (D5)");
        report();
    }

    @Test
    @Order(2)
    void actions_libraryFnsInRequiresMatchesAndValues() throws Exception {
        List<Object> two = List.of(line(ANN, 6_000), line(BEN, 3_000));
        for (Rec tier : tiers()) {
            for (long sold : List.of(0L, 9L, 12_345L)) {
                Rec s = state(10_000_000, two, tier, sold);
                for (long received : List.of(10_024_999L, 10_025_000L, 10_049_999L, 10_050_000L, 10_099_999L, 10_100_000L)) {
                    shop.check("buy", s, List.of(), at(ANN, received, 12_000_000), ANN);
                }
                for (Rec next : tiers()) shop.check("setTier", s, List.of(next), null, OWNER);
                shop.check("setTier", s, List.of(rec("Basic")), null, ANN);
                for (Rec l : List.of(line(ANN, 1_000), line(ANN, 1_001), rec("Line", Bytes.of(new byte[27]), n(1)))) {
                    shop.check("addLine", s, List.of(l), null, OWNER);
                }
                shop.check("feeOn", s, List.of(n(1_000_000)), null, null);
                shop.check("soldDigits", s, List.of(), null, null);
            }
        }
        // By hand: a Basic shop's fee is 1% (100 bps of the library's rate); Gold is 0.5% before 10 sales, 0.25% after.
        Rec basic = state(10_000_000, two, rec("Basic"), 0);
        assertEquals("refused", shop.check("buy", basic, List.of(), at(ANN, 10_099_999, 0), ANN));
        assertTrue(shop.check("buy", basic, List.of(), at(ANN, 10_100_000, 0), ANN).startsWith("ok:"));
        assertEquals("refused", shop.check("buy", state(10_000_000, two, rec("Gold", n(9)), 0), List.of(), at(ANN, 10_049_999, 0), ANN));
        assertTrue(shop.check("buy", state(10_000_000, two, rec("Gold", n(10)), 0), List.of(), at(ANN, 10_025_000, 0), ANN).startsWith("ok:"));
        // A sale moves a Gold shop's count: the library's enum built and matched in the contract.
        var lines = (org.julclang.core.types.JulcList<org.julclang.state.examples.dsl.Fees.Line>) shop.java(two);
        var after = new org.julclang.state.examples.dsl.Shop.State(OWNER, n(10_000_000), lines, new org.julclang.state.examples.dsl.Fees.Gold(n(10)),
                n(1), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertEquals("ok:" + result(after, null), shop.check("buy", state(10_000_000, two, rec("Gold", n(9)), 0), List.of(),
                at(ANN, 10_050_000, 0), ANN));
        report();
    }

    @Test
    @Order(3)
    void close_payoutsFromLibraryFns() throws Exception {
        for (List<Object> lines : List.<List<Object>>of(List.of(), List.of(line(ANN, 6_000), line(BEN, 3_000)), List.of(line(ANN, 10_000)))) {
            for (long balance : List.of(0L, 7L, 25_000_003L)) {
                shop.check("close", state(1, lines, rec("Basic"), 0), List.of(), at(OWNER, 0, balance), OWNER);
            }
        }
        // By hand: 60% and 30% of 25,000,003 (truncated), the remainder to the owner.
        String d = shop.check("close", state(1, List.of(line(ANN, 6_000), line(BEN, 3_000)), rec("Basic"), 0), List.of(),
                at(OWNER, 0, 25_000_003), OWNER);
        var pays = org.julclang.core.types.JulcList.of(new org.julclang.state.api.Payout(ANN, n(15_000_001)),
                new org.julclang.state.api.Payout(BEN, n(7_500_000)), new org.julclang.state.api.Payout(OWNER, n(2_500_002)));
        assertEquals("ok:" + result(null, pays), d);
        report();
    }

    static void report() {
        System.out.println("[J3 library] Shop (fees.jsc): " + shop.calls + " calls, interpreter = JVM = transition program = validator ("
                + shop.refusals + " refusals, " + shop.validatorRuns + " validator runs)");
        assertTrue(shop.refusals > 0 && shop.refusals < shop.calls, "accepted and refused calls");
    }

    /** The fifth leg: the JavaScript interpreter evaluates the library's fns from the IR export (version 2's libraries). */
    @Test
    @Order(Integer.MAX_VALUE)
    void fiveWay_theJavaScriptInterpreterAgrees() throws Exception {
        assertTrue(shop.irJson.contains("\"library\": \"org.julclang.state.examples.dsl.Fees\""), "the IR carries the library");
        int n = JsLeg.check(shop);
        System.out.println("[J3 five-way] Shop (fees.jsc): " + n + " calls agree on all five legs, the library's fns evaluated by both interpreters");
        assertTrue(n > 100, n + " calls");
    }
}
