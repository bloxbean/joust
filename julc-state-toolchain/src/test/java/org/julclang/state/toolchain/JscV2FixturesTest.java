package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.JscV2;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.julclang.state.toolchain.TransitionParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review C10: accepted {@code .jsc} 2 programs do not only lower to text, they compile. Every positive fixture below is
 * built by {@link Toolchain#build} (javac attribution, JOUST's processor, JuLC), and some are run: the reference
 * interpreter against the transition program the build made. The fixtures are the cases the reviewers found broken in
 * the generated Java (Boolean collections, pass-through imports, strings with escapes, record fields named like list
 * properties, a capitalised const before a block) and the positive forms of the checker's rules.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2FixturesTest {

    static final Map<String, String> FIXTURES = new LinkedHashMap<>();
    static Toolchain.Result built;

    static void fixture(String name, String body) {
        FIXTURES.put("f/" + name + ".jsc", "jsc 2\npackage f\n" + body + "\n");
    }

    static {
        // Codex C4, design F7: Booleans in lists and options.
        fixture("BoolContains", "contract BoolContains {\n state { n: Int }\n init() { return { n: 0 } }\n view v(): Bool = [true].contains(true)\n"
                + " action a(flags: List<Bool>, b: Bool) { require(flags.contains(b), \"x\")\n return state }\n}");
        fixture("BoolEquality", "contract BoolEquality {\n state { n: Int }\n init() { return { n: 0 } }\n"
                + " action sameLists(a: List<Bool>, b: List<Bool>) { return { n: a == b ? 1 : 0 } }\n"
                + " action sameOptions(a: Option<Bool>, b: Option<Bool>) { return { n: a == b ? 1 : 0 } }\n"
                + " action flags(a: List<Bool>) { return { n: a.filter(x => x).length + a.fold(0, (acc, x) => x ? acc + 1 : acc) } }\n}");
        // Codex C5: types that are only passed through still import JulcList and Optional.
        fixture("ImportsList", "contract ImportsList {\n state { ks: List<Key> }\n init(ks: List<Key>) { return { ks } }\n}");
        fixture("ImportsOption", "contract ImportsOption {\n state { k: Option<Key> }\n init(k: Option<Key>) { return { k } }\n}");
        // Codex C8: escapes in require messages; a line break written \n.
        fixture("Strings", "contract Strings {\n state { n: Int }\n init() { return { n: 0 } }\n"
                + " action a() { require(n >= 0, \"say \\\"no\\\" \\\\ then\\nstop\")\n return state }\n}");
        // Codex C7: record fields named like list properties are readable.
        fixture("RecordLength", "type R = { length: Int, head: Int, isEmpty: Bool }\ncontract RecordLength {\n state { r: R, xs: List<Int> }\n"
                + " init() { return { r: R { length: 3, head: 4, isEmpty: false }, xs: [1, 2] } }\n"
                + " view v(): Int = r.length + r.head + xs.length\n view w(): Bool = r.isEmpty || xs.isEmpty\n}");
        // Design F14: a capitalised const before a block is a name, not a constructor.
        fixture("UpperConst", "contract UpperConst {\n state { count: Int }\n init() { return { count: 0 } }\n"
                + " action a() {\n const MAX = 10\n return if count > MAX { state } else { { ...state, count: count + 1 } } }\n}");
        // Block lambdas, value ifs with consts, ?? with parentheses, a const typed State, time stored and paid (warned).
        fixture("Blocks", "type Share = { to: Key, bps: Int }\ncontract Blocks {\n"
                + " state { owner: Key, xs: List<Int>, stamp: Int, shares: List<Share> }\n"
                + " init(shares: List<Share>) { return { owner: caller, xs: [1, 2, 3], stamp: 0, shares } }\n"
                + " action a(c: Bool, o: Option<Bool>) {\n"
                + "  require(xs.all(x => { const y = x + 1\n y > 1 }), \"x\")\n"
                + "  require((o ?? c) || true, \"y\")\n"
                + "  const next: State = if c { const t = validFrom\n { ...state, stamp: t + 1 } } else { state }\n"
                + "  return next }\n"
                + " action b() { require(before(stamp + 60000), \"stale\")\n return state }\n"
                + " destroy d() only owner { for (const s of shares) { pay(s.to, max(0, balance * s.bps / 10_000 - stamp)) }\n"
                + "  pay(owner, max(0, balance - shares.fold(0, (acc, s) => acc + max(0, balance * s.bps / 10_000 - stamp)))) }\n}");
        // Keys: asKey on a literal is checked when compiled; on bytes, when called.
        fixture("Keys", "contract Keys {\n state { owner: Key }\n init() { return { owner: asKey(#" + "11".repeat(28) + ") } }\n"
                + " action set(b: Bytes) only owner { return { ...state, owner: asKey(b) } }\n}");
        // Recursion: direct, in a ?: and in a value if.
        fixture("Recursion", "fn fact(n: Int): Int = n <= 1 ? 1 : n * fact(n - 1)\n"
                + "fn digits(n: Int): Int = if n < 10 { 1 } else { 1 + digits(n / 10) }\n"
                + "contract Recursion {\n state { n: Int }\n init() { return { n: 0 } }\n action a(k: Int) { return { n: fact(k) + digits(k) } }\n}");
        // Codex C13: equality of an empty record; C11: Booleans compared by value in the interpreter too.
        fixture("EmptyRecord", "type E = {}\ntype H = { e: E, n: Int }\ncontract EmptyRecord {\n state { n: Int }\n init() { return { n: 0 } }\n"
                + " action a(h: H) { const r = E {}\n return { n: r == r && h == H { e: r, n: 1 } ? 1 : 0 } }\n}");
        fixture("BoolIdentity", "contract BoolIdentity {\n state { n: Int }\n init() { return { n: 0 } }\n"
                + " action a(x: Bool, y: Bool) { return { n: x == y ? 1 : 0 } }\n"
                + " action lists(x: List<Bool>, y: List<Bool>) { return { n: x == y ? 1 : 0 } }\n"
                + " action options(x: Option<Bool>, y: Option<Bool>) { return { n: x == y ? 1 : 0 } }\n}");
        // Review F3, C1: each Map and BigMap operation alone, with no list, pay or other construct that would import
        // what its helpers name. Every one must compile with javac and JuLC.
        String k = "#" + "ab".repeat(4);
        fixture("MapSetOnly", "contract MapSetOnly {\n state { m: Map<Bytes, Int> }\n init() { return { m: Map.empty() } }\n"
                + " action a(k: Bytes, v: Int) { return { m: m.set(k, v) } }\n}");
        fixture("MapInputOnly", "contract MapInputOnly {\n state { n: Int }\n init() { return { n: 0 } }\n"
                + " action a(m: Map<Bytes, Int>) { return { n: m.size } }\n}");
        fixture("MapKeyInputOnly", "contract MapKeyInputOnly {\n state { n: Int }\n init() { return { n: 0 } }\n"
                + " action a(m: Map<Key, Bool>) { return { n: m.size } }\n}");
        fixture("MapBoolOnly", "contract MapBoolOnly {\n state { m: Map<Bytes, Bool> }\n init() { return { m: Map.empty() } }\n"
                + " action a(k: Bytes) { return { m: m.set(k, !(m.get(k) ?? false)) } }\n}");
        fixture("MapGetOnly", "contract MapGetOnly {\n state { m: Map<Bytes, Int>, n: Int }\n init() { return { m: Map.empty(), n: 0 } }\n"
                + " action a(k: Bytes) { return { ...state, n: m.get(k) ?? 0 } }\n}");
        fixture("MapHasOnly", "contract MapHasOnly {\n state { m: Map<Bytes, Int> }\n init() { return { m: Map.empty() } }\n"
                + " action a(k: Bytes) { require(m.has(k), \"x\")\n return state }\n}");
        fixture("MapDeleteOnly", "contract MapDeleteOnly {\n state { m: Map<Bytes, Int> }\n init() { return { m: Map.empty() } }\n"
                + " action a(k: Bytes) { return { m: m.delete(k) } }\n}");
        fixture("MapAnyOnly", "contract MapAnyOnly {\n state { m: Map<Bytes, Int> }\n init() { return { m: Map.empty() } }\n"
                + " action a() { require(m.any((k, v) => v > 0), \"x\")\n return state }\n}");
        fixture("MapFoldOnly", "contract MapFoldOnly {\n state { m: Map<Bytes, Int>, n: Int }\n init() { return { m: Map.empty(), n: 0 } }\n"
                + " action a() { return { ...state, n: m.fold(0, (acc, k, v) => acc + v) } }\n}");
        fixture("MapEqOnly", "contract MapEqOnly {\n state { m: Map<Bytes, Int> }\n init() { return { m: Map.empty() } }\n"
                + " action a(o: Map<Bytes, Int>) { require(o == m, \"x\")\n return state }\n}");
        fixture("BigSetOnly", "contract BigSetOnly {\n state { b: BigMap<Bytes, Int> }\n init() { return { b: BigMap.empty() } }\n"
                + " action a(k: Bytes) { return { b: b.set(k, b[k] + 1) } }\n}");
        fixture("BigBytesOnly", "contract BigBytesOnly {\n state { b: BigMap<Bytes, Bytes> }\n init() { return { b: BigMap.empty() } }\n"
                + " action a(k: Bytes) { return { b: b.set(k, b.get(k) ?? " + k + ") } }\n}");
        fixture("BigHasOnly", "contract BigHasOnly {\n state { b: BigMap<Bytes, Int> }\n init() { return { b: BigMap.empty() } }\n"
                + " action a(k: Bytes) { require(!b.has(k), \"x\")\n return state }\n}");
        fixture("BigDeleteOnly", "contract BigDeleteOnly {\n state { b: BigMap<Bytes, Int> }\n init() { return { b: BigMap.empty() } }\n"
                + " action a(k: Bytes) { return { b: b.delete(k) } }\n}");
        // M1b review S1: recursion as deep as the budget allows (the chain accepts down(3000) at 20% of its CPU, 56% of its
        // memory); both interpreters must answer it, not report a limit or refuse.
        fixture("Deep", "fn down(n: Int): Int = n <= 0 ? 0 : 1 + down(n - 1)\ncontract Deep {\n state { count: Int }\n"
                + " init() { return { count: 0 } }\n action go(n: Int) { return { ...state, count: down(n) } }\n}");
        // A view reads a nested record field through an accessor helper.
        fixture("Nested", "type In = { k: Key, v: Int }\ntype Out = { i: In, tag: Bytes }\ncontract Nested {\n state { o: Option<Out> }\n"
                + " init(o: Option<Out>) { return { o } }\n"
                + " view v(d: Out): Int = (o ?? d).i.v\n"
                + " action a(xs: List<Out>) { return { o: xs.find(x => x.i.v > 0) } }\n}");
    }

    @BeforeAll
    static void build() {
        built = Toolchain.build(FIXTURES, Map.of());
    }

    @Test
    void everyPositiveFixtureCompiles_withJavacAndJulc() {
        assertTrue(built.ok(), built.diagnostics().toString());
        var names = built.contracts().stream().map(Toolchain.Contract::name).toList();
        assertEquals(FIXTURES.size(), names.size(), names.toString());
        for (var c : built.contracts()) {
            System.out.println("[C10 fixtures] " + c.name() + ": " + c.scriptSizeBytes() + " bytes, transitions " + c.transitions().keySet());
        }
        // The time stored and paid in Blocks is warned about, with its line and column, on the npm path (F13, D11).
        var warnings = built.diagnostics().stream().filter(d -> d.severity() == Toolchain.Severity.WARNING && "jsc".equals(d.source())).toList();
        assertTrue(warnings.stream().anyMatch(d -> d.file().equals("f/Blocks.jsc") && d.message().contains("stores a value of the validity window in stamp")),
                warnings.toString());
        assertTrue(warnings.stream().anyMatch(d -> d.file().equals("f/Blocks.jsc") && d.message().contains("sets this payout")), warnings.toString());
        assertTrue(warnings.stream().allMatch(d -> d.line() > 0 && d.col() > 0), warnings.toString());
    }

    static Map<String, String> transitions(String contract) {
        return built.contracts().stream().filter(c -> c.name().equals(contract)).findFirst().orElseThrow().transitions();
    }

    /** Each interpreter's contract, and every call it answered, for the JavaScript leg (ADR-0018 D15, M1b). */
    static final Map<Interpreter, String> NAMES = new IdentityHashMap<>();
    static final Map<String, List<JsLeg.Call>> JS_CALLS = new LinkedHashMap<>();

    static Interpreter interp(String contract) {
        var in = JscV2.interpreter(FIXTURES.get("f/" + contract + ".jsc"), contract + ".jsc", Interpreter.JDK_HASHES);
        NAMES.put(in, contract);
        return in;
    }

    /** The interpreter's next state as data (records Constr 0, Bool Constr 0/1, Option Constr 0 [x] / 1 []: D5). */
    static PlutusData data(Object v) {
        return switch (v) {
            case BigInteger i -> com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(i);
            case Boolean b -> com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(b ? 1 : 0);
            case Bytes b -> com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(b.bytes());
            case Opt o -> o.present() ? com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0, data(o.value()))
                    : com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(1);
            case List<?> l -> com.bloxbean.cardano.client.plutus.spec.ListPlutusData.builder()
                    .plutusDataList(l.stream().map(JscV2FixturesTest::data).toList()).build();
            case Rec r -> com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                    r.fields().stream().map(JscV2FixturesTest::data).toArray(PlutusData[]::new));
            default -> throw new IllegalArgumentException(String.valueOf(v));
        };
    }

    static String interpreted(Interpreter in, String method, Rec state, List<Object> args) {
        Interpreter.Outcome o = null;
        Interpreter.Refused refused = null;
        try {
            o = in.transition(method, state, args, null);   // compared with the transition program (no modifiers, §2.6)
        } catch (Interpreter.Refused e) {
            refused = e;
        }
        String want = refused != null ? "refused" : encoded(o);
        String contract = NAMES.get(in);
        JS_CALLS.computeIfAbsent(contract, k -> new ArrayList<>())
                .add(JsLeg.transitionCall(method, state, args, null, want, o, refused, contract + "." + method + " " + args));
        return want;
    }

    /** An outcome as the transition program's result: the next state, no payouts. */
    static String encoded(Interpreter.Outcome o) {
        return "ok:" + com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0, data(o.value())),
                com.bloxbean.cardano.client.plutus.spec.ListPlutusData.builder().build()).serializeToHex();
    }

    /**
     * ADR-0018 D15 (M1b): every call above, on the JavaScript reference interpreter over the fixture's IR export, agrees
     * with the Java interpreter and the transition program. Runs last.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void theJavaScriptInterpreterAgrees() throws Exception {
        int total = 0;
        for (var e : JS_CALLS.entrySet()) {
            String irJson = JscV2.lower(FIXTURES.get("f/" + e.getKey() + ".jsc"), e.getKey() + ".jsc").irJson();
            total += JsLeg.check(e.getKey(), irJson, e.getValue(), (method, o) -> encoded(o));
        }
        System.out.println("[C10 fixtures] " + total + " calls of " + JS_CALLS.keySet() + ": JavaScript interpreter = Java interpreter"
                + " = transition program");
        assertTrue(total > 60, "the tests above recorded their calls: " + total);
    }

    static PlutusData argsOf(List<Object> args) {
        return com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0, args.stream().map(JscV2FixturesTest::data).toArray(PlutusData[]::new));
    }

    /** Booleans in lists and options (C4): the interpreter and the transition programs agree. */
    @Test
    void booleanFixturesRun_interpreterAgainstTheTransitionPrograms() {
        var in = interp("BoolEquality");
        var t = transitions("BoolEquality");
        Rec s = new Rec("State", List.of(BigInteger.ZERO));
        int n = 0;
        List<List<Object>> lists = List.of(List.of(), List.of(true), List.of(false), List.of(true, false), List.of(false, true), List.of(true, true));
        for (List<Object> a : lists) {
            for (List<Object> b : lists) {
                String want = interpreted(in, "sameLists", s, List.of(a, b));
                assertEquals(want, evaluate(t.get("sameLists"), data(s), NONE, argsOf(List.of(a, b)), NONE), a + " == " + b);
                n++;
            }
            String want = interpreted(in, "flags", s, List.of(a));
            assertEquals(want, evaluate(t.get("flags"), data(s), NONE, argsOf(List.of(a)), NONE), "flags " + a);
            n++;
        }
        for (Opt a : List.of(Opt.NONE, Opt.some(true), Opt.some(false))) {
            for (Opt b : List.of(Opt.NONE, Opt.some(true), Opt.some(false))) {
                String want = interpreted(in, "sameOptions", s, List.of(a, b));
                assertEquals(want, evaluate(t.get("sameOptions"), data(s), NONE, argsOf(List.of(a, b)), NONE), a + " == " + b);
                n++;
            }
        }
        // By hand: [true, false] == [true, false] and Some(false) == Some(false); [true] != [true, true].
        assertEquals(interpreted(in, "sameLists", s, List.of(List.of(true, false), List.of(true, false))),
                "ok:" + result(new Rec("State", List.of(BigInteger.ONE))));
        assertEquals(interpreted(in, "sameLists", s, List.of(List.of(true), List.of(true, true))),
                "ok:" + result(new Rec("State", List.of(BigInteger.ZERO))));
        var c = interp("BoolContains");
        var ct = transitions("BoolContains");
        for (List<Object> flags : lists) {
            for (boolean b : List.of(true, false)) {
                assertEquals(interpreted(c, "a", s, List.of(flags, b)), evaluate(ct.get("a"), data(s), NONE, argsOf(List.of(flags, b)), NONE));
                n++;
            }
        }
        System.out.println("[C4] " + n + " Boolean collection calls: interpreter = transition program");
    }

    static String result(Rec next) {
        return com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0, com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                data(next)), com.bloxbean.cardano.client.plutus.spec.ListPlutusData.builder().build()).serializeToHex();
    }

    /** Codex C11 and C13: distinct Boolean objects of equal value, and empty records, compare equal on both legs. */
    @Test
    @SuppressWarnings("removal")
    void booleansByValue_andEmptyRecords() {
        var in = interp("BoolIdentity");
        var t = transitions("BoolIdentity");
        Rec s = new Rec("State", List.of(BigInteger.ZERO));
        Boolean a = new Boolean(true), b = new Boolean(true);
        assertNotSame(a, b);
        var calls = Map.<String, List<Object>>of("a", List.of(a, b), "lists", List.of(List.of(a, false), List.of(b, new Boolean(false))),
                "options", List.of(Opt.some(a), Opt.some(b)));
        for (var c : calls.entrySet()) {
            String want = "ok:" + result(new Rec("State", List.of(BigInteger.ONE)));
            assertEquals(want, interpreted(in, c.getKey(), s, c.getValue()), "interpreter: " + c.getKey());
            assertEquals(want, evaluate(t.get(c.getKey()), data(s), NONE, argsOf(c.getValue()), NONE), "transition: " + c.getKey());
        }
        var e = interp("EmptyRecord");
        var et = transitions("EmptyRecord");
        for (long n : List.of(1L, 2L)) {
            var h = new Rec("H", List.of(new Rec("E", List.of()), BigInteger.valueOf(n)));
            String want = "ok:" + result(new Rec("State", List.of(BigInteger.valueOf(n == 1 ? 1 : 0))));
            assertEquals(want, interpreted(e, "a", s, List.of(h)));
            assertEquals(want, evaluate(et.get("a"), data(s), NONE, argsOf(List.of(h)), NONE));
        }
    }

    /**
     * M1b review S1: recursion of depth 500 to 3,000, which the transition program (as the chain) evaluates within its
     * budget, gets the same answer from both interpreters (the JavaScript one in the fifth-leg test below): no limit and
     * no refusal.
     */
    @Test
    void deepRecursion_thatTheBudgetAllows_isAnsweredByEveryLeg() {
        var in = interp("Deep");
        var t = transitions("Deep");
        Rec s = new Rec("State", List.of(BigInteger.ZERO));
        for (long n : List.of(500L, 1_000L, 3_000L)) {
            String want = "ok:" + result(new Rec("State", List.of(BigInteger.valueOf(n))));
            assertEquals(want, interpreted(in, "go", s, List.of(BigInteger.valueOf(n))), "the Java interpreter, depth " + n);
            assertEquals(want, evaluate(t.get("go"), data(s), NONE, argsOf(List.of(BigInteger.valueOf(n))), NONE), "the transition program, depth " + n);
        }
    }

    /** Recursion, nested records through accessor helpers, asKey at run time: interpreter against the transition programs. */
    @Test
    void otherFixturesRun() {
        var in = interp("Recursion");
        var t = transitions("Recursion");
        Rec s = new Rec("State", List.of(BigInteger.ZERO));
        for (long k : List.of(0L, 1L, 5L, 12L, 25L)) {
            assertEquals(interpreted(in, "a", s, List.of(BigInteger.valueOf(k))),
                    evaluate(t.get("a"), data(s), NONE, argsOf(List.of(BigInteger.valueOf(k))), NONE), "fact/digits " + k);
        }
        assertEquals("ok:" + result(new Rec("State", List.of(BigInteger.valueOf(120 + 1)))), interpreted(in, "a", s, List.of(BigInteger.valueOf(5))));
        var keys = interp("Keys");
        var kt = transitions("Keys");
        Rec ks = new Rec("State", List.of(Bytes.of(new byte[28])));
        for (byte[] b : List.of(new byte[28], new byte[27], new byte[29])) {
            assertEquals(interpreted(keys, "set", ks, List.of(Bytes.of(b))), evaluate(kt.get("set"), data(ks), NONE, argsOf(List.of(Bytes.of(b))), NONE));
        }
        var nested = interp("Nested");
        var nt = transitions("Nested");
        Rec none = new Rec("State", List.of(Opt.NONE));
        Rec out1 = new Rec("Out", List.of(new Rec("In", List.of(Bytes.of(new byte[28]), BigInteger.ZERO)), Bytes.of(new byte[]{1})));
        Rec out2 = new Rec("Out", List.of(new Rec("In", List.of(Bytes.of(new byte[28]), BigInteger.TWO)), Bytes.of(new byte[]{2})));
        Rec shortKey = new Rec("Out", List.of(new Rec("In", List.of(Bytes.of(new byte[3]), BigInteger.TWO)), Bytes.of(new byte[]{2})));
        for (List<Object> xs : List.<List<Object>>of(List.of(), List.of(out1), List.of(out1, out2), List.of(out2, shortKey))) {
            assertEquals(interpreted(nested, "a", none, List.of(xs)), evaluate(nt.get("a"), data(none), NONE, argsOf(List.of(xs)), NONE), "find " + xs);
        }
        assertEquals("refused", interpreted(nested, "a", none, List.of(List.of(out2, shortKey))), "a nested short key is a checked input");
    }
}
