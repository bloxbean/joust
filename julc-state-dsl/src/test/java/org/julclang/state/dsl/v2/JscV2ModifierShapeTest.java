package org.julclang.state.dsl.v2;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1 hardening (security review S5 and S6 on #76). The IR reader takes a method's modifiers only in the shapes the
 * checker emits, as the JavaScript reader does ({@code validate.js}): {@code only f} on a Key field of the state,
 * {@code after f}/{@code before f} on an Int field, {@code payable}/{@code open} without a field, {@code batched} plain
 * or {@code batched only} on an action without {@code only}/{@code after}/{@code before}, none twice, an init with only
 * {@code payable}, a view with none, no payable destroy. The interpreter never skips a modifier without its field.
 * And a transaction whose caller is not among its signers is the caller's error in the interpreter (semantics §2).
 */
class JscV2ModifierShapeTest {

    static final Path FIXTURES = JscV2IrTest.JS_FIXTURES;

    static String fixture(String name) throws Exception {
        return Files.readString(FIXTURES.resolve(name + ".ir.json"));
    }

    static void refused(String json, String fragment) {
        var e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    /** {@code json} with the {@code mods} list of method {@code name} (of kind {@code kind}) edited as text. */
    static String mods(String json, String kind, String name, UnaryOperator<String> edit) {
        Matcher m = Pattern.compile("(\"kind\": \"" + kind + "\",\\s*\"name\": \"" + name + "\",.*?\"mods\": )(\\[[^\\]]*\\])", Pattern.DOTALL)
                .matcher(json);
        assertTrue(m.find(), kind + " " + name);
        return json.substring(0, m.start(2)) + edit.apply(m.group(2)) + json.substring(m.end(2));
    }

    @Test
    void theReader_refusesModifierShapesTheCheckerNeverEmits() throws Exception {
        String pool = fixture("Pool"), vault = fixture("Vault");
        IrJson.read(pool);
        IrJson.read(vault);
        // 'only' without a field: read and then skipped before, so Pool's withdraw was accepted unsigned.
        refused(mods(pool, "action", "withdraw", x -> "[{ \"name\": \"only\" }]"), "only names a state field, not null");
        refused(mods(pool, "action", "withdraw", x -> "[{ \"name\": \"only\", \"field\": null }]"), "only names a state field, not null");
        refused(mods(vault, "action", "withdraw", x -> x.replace("\"field\": \"unlockAt\"", "\"field\": null")), "after names a state field, not null");
        // A field that is not there, or of the wrong type.
        refused(mods(pool, "action", "withdraw", x -> x.replace("admin", "nobody")), "only nobody: the state has no field nobody");
        refused(mods(pool, "action", "withdraw", x -> x.replace("admin", "donated")), "only donated: donated is of type Int, not Key");
        refused(mods(vault, "action", "withdraw", x -> x.replace("\"unlockAt\"", "\"owner\"")), "after owner: owner is of type Key, not Int");
        refused(mods(vault, "action", "withdraw", x -> x.replace("\"after\", \"field\": \"unlockAt\"", "\"before\", \"field\": \"owner\"")),
                "before owner: owner is of type Key, not Int");
        refused(mods(pool, "action", "withdraw", x -> x.replace("\"field\": \"admin\"", "\"field\": 7")), "a modifier's field is a name");
        refused(mods(pool, "action", "withdraw", x -> x.replace("\"field\": \"admin\"", "\"field\": \"admin\", \"when\": 1")), "unknown field \"when\" in a modifier");
        // Duplicates; an init's, a view's and a destroy's modifiers; payable and open with a field.
        refused(mods(pool, "action", "withdraw", x -> "[{ \"name\": \"only\", \"field\": \"admin\" }, { \"name\": \"only\", \"field\": \"admin\" }]"),
                "the modifier only is given twice");
        refused(mods(vault, "init", "init", x -> "[{ \"name\": \"payable\" }, { \"name\": \"only\", \"field\": \"owner\" }]"), "an init takes only payable, not only");
        refused(mods(vault, "view", "total", x -> "[{ \"name\": \"open\" }]"), "a view takes no modifiers, not open");
        refused(mods(vault, "destroy", "close", x -> x.replace("[", "[{ \"name\": \"payable\" }, ")), "a destroy cannot be payable");
        refused(mods(vault, "action", "deposit", x -> "[{ \"name\": \"payable\", \"field\": \"owner\" }]"), "payable names no field, not owner");
        refused(mods(pool, "action", "buy", x -> x.replace("{ \"name\": \"open\" }", "{ \"name\": \"open\", \"field\": \"admin\" }")), "open names no field, not admin");
        refused(mods(pool, "action", "withdraw", x -> "[{ \"name\": \"sealed\" }]"), "unknown modifier sealed");
        // batched: plain or 'batched only', on an action, never beside only/after/before.
        refused(mods(pool, "destroy", "close", x -> x.replace("[", "[{ \"name\": \"batched\", \"field\": \"only\" }, ")), "only an action can be batched, not a destroy");
        refused(mods(pool, "action", "donate", x -> x.replace("{ \"name\": \"batched\" }", "{ \"name\": \"batched\", \"field\": \"admin\" }")),
                "batched is plain or batched only, not admin");
        refused(mods(pool, "action", "donate", x -> x.replace("]", ", { \"name\": \"only\", \"field\": \"admin\" }]")), "a batched action cannot use only");
        refused(mods(vault, "action", "withdraw", x -> x.replace("]", ", { \"name\": \"batched\" }]")), "a batched action cannot use only");
    }

    /** #80 review S3: pays is recomputed, a paying method needs 'only f' or 'open', no batched action with BigMap state. */
    @Test
    void theReader_recomputesPays_andRequiresAnAudience() throws Exception {
        String pool = fixture("Pool"), counter = fixture("Counter2"), ledger = fixture("Ledger");
        IrJson.read(counter);
        IrJson.read(ledger);
        refused(mods(pool, "action", "withdraw", x -> "[]"), "it pays, sends, mints, burns or deposits, so it says who may call it ('only f', 'open', quorum or authorized)");
        refused(mods(pool, "action", "buy", x -> x.replace(", { \"name\": \"open\" }", "")), "so it says who may call it");
        refused(mods(counter, "destroy", "close", x -> "[]"), "so it says who may call it");
        refused(withMethodField(pool, "withdraw", "\"pays\": true", "\"pays\": false"), "pays is false, but the body pays");
        refused(withMethodField(counter, "increment", "\"pays\": false", "\"pays\": true"), "pays is true, but the body does not pay");
        refused(mods(ledger, "action", "transfer", x -> "[{ \"name\": \"batched\" }]"), "a contract with BigMap state has no batched action");
        // A statement's own pays flag (an if in Branches' settle).
        String branches = fixture("Branches");
        int at = branches.indexOf("\"stmt\": \"If\"");
        int flag = branches.indexOf("\"pays\": true", at);
        refused(branches.substring(0, flag) + "\"pays\": false" + branches.substring(flag + "\"pays\": true".length()), "pays is false, but its blocks pay");
    }

    /** #80 review S6: malformed modifiers are refusals (no NullPointerException or ClassCastException); kinds as written. */
    @Test
    void theReader_refusesMalformedModifiersAndKindsCleanly() throws Exception {
        String pool = fixture("Pool");
        refused(mods(pool, "action", "withdraw", x -> "[{ \"name\": null, \"field\": \"admin\" }]"), "a modifier's name is not a string");
        refused(mods(pool, "action", "withdraw", x -> "[{ \"field\": \"admin\" }]"), "a modifier's name is not a string");
        refused(mods(pool, "action", "withdraw", x -> "[\"only\"]"), "a modifier is not an object");
        refused(mods(pool, "action", "withdraw", x -> "[null]"), "a modifier is not an object");
        refused(pool.replaceFirst("\"kind\": \"action\"", "\"kind\": \"ACTION\""), "unknown kind ACTION");
        refused(pool.replaceFirst("\"kind\": \"init\"", "\"kind\": \"INIT\""), "unknown kind INIT");
        refused(pool.replaceFirst("\"kind\": \"init\"", "\"kind\": \"ınit\""), "unknown kind");
        // #80 review r3 note: a null or missing node or statement kind is a refusal, not a NullPointerException.
        refused(pool.replaceFirst("\"node\": \"StateRef\"", "\"node\": null"), "unknown node null");
        refused(pool.replaceFirst("\"stmt\": \"Require\"", "\"stmt\": null"), "unknown statement null");
    }

    /** #80 review C4, S7, F7: the shared mutants (js/jsc-interpreter/test/mutants), refused as the JavaScript reader does. */
    @Test
    void theReader_refusesAPayUnderAnExpressionOrInAFn_andADestroyWithoutPay() throws Exception {
        Path mutants = Path.of("../js/jsc-interpreter/test/mutants");
        refused(Files.readString(mutants.resolve("pay-in-value-block.json")),
                "method withdraw: a pay inside an expression or a fn, where the checker allows none (JSC0413)");
        refused(Files.readString(mutants.resolve("pay-in-value-if.json")), "method withdraw: a pay inside an expression or a fn");
        refused(Files.readString(mutants.resolve("pay-in-fn.json")), "fn total: a pay inside an expression or a fn");
        refused(Files.readString(mutants.resolve("destroy-without-pay.json")), "method close: a destroy pays out what the instance holds");
    }

    /** {@code json} with {@code from} replaced by {@code to} in method {@code name}'s own fields (before its body). */
    static String withMethodField(String json, String name, String from, String to) {
        int m = json.indexOf("\"name\": \"" + name + "\",");
        assertTrue(m > 0, name);
        int i = json.indexOf(from, m);
        assertTrue(i > 0, from);
        return json.substring(0, i) + to + json.substring(i + from.length());
    }

    static final byte[] OWNER = filled(0x0a), ANN = filled(0xa1);

    static byte[] filled(int b) {
        byte[] k = new byte[28];
        java.util.Arrays.fill(k, (byte) b);
        return k;
    }

    static Interpreter.Tx tx(byte[] caller) {
        return new Interpreter.Tx(Interpreter.Bytes.of(caller), BigInteger.ZERO, BigInteger.valueOf(10_000_000), BigInteger.valueOf(1500),
                BigInteger.valueOf(2000));
    }

    static Interpreter.Rec vaultState() {
        return new Interpreter.Rec("State", List.of(Interpreter.Bytes.of(OWNER), BigInteger.valueOf(1500), BigInteger.valueOf(10_000_000)));
    }

    @Test
    void theInterpreter_neverSkipsAModifierWithoutItsField() throws Exception {
        Ir.Program p = IrJson.read(fixture("Vault"));
        var methods = new ArrayList<Ir.Method>();
        for (Ir.Method m : p.methods()) {
            methods.add(!m.name().equals("withdraw") ? m : new Ir.Method(m.kind(), m.name(), m.params(), List.of(new Ir.Mod("only", null)),
                    m.checked(), m.usesCtx(), m.pays(), m.body(), m.resultType(), m.pos()));
        }
        var broken = new Ir.Program(p.pkg(), p.fileName(), p.name(), p.options(), p.records(), p.enums(), p.state(), methods, p.fns(),
                p.timeFields(), p.recursiveFns(), p.warnings());
        var in = new Interpreter(broken, Interpreter.JDK_HASHES);
        var e = assertThrows(IllegalArgumentException.class, () -> in.call("withdraw", vaultState(), List.of(BigInteger.ONE), tx(ANN)));
        assertTrue(e.getMessage().contains("'only' without a state field"), e.getMessage());
        // As read, the same call is a modifier refusal (Ann is not the owner).
        var ok = new Interpreter(p, Interpreter.JDK_HASHES);
        assertEquals("modifier:only owner", assertThrows(Interpreter.Refused.class,
                () -> ok.call("withdraw", vaultState(), List.of(BigInteger.ONE), tx(ANN))).refusal());
    }

    @Test
    void aCallerWhoDidNotSign_isTheCallersError() throws Exception {
        var in = new Interpreter(IrJson.read(fixture("Vault")), Interpreter.JDK_HASHES);
        var annByOwner = tx(ANN).signedBy(List.of(Interpreter.Bytes.of(OWNER)));
        for (boolean modifiers : List.of(true, false)) {
            var e = assertThrows(IllegalArgumentException.class, () -> {
                if (modifiers) in.call("withdraw", vaultState(), List.of(BigInteger.ONE), annByOwner);
                else in.transition("withdraw", vaultState(), List.of(BigInteger.ONE), annByOwner);
            });
            assertTrue(e.getMessage().contains("the transaction's caller is not among its signers"), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> in.call("withdraw", vaultState(), List.of(BigInteger.ONE), tx(ANN).signedBy(List.of())));
        // Both sign: 'only owner' holds and the call is answered.
        var both = tx(ANN).signedBy(List.of(Interpreter.Bytes.of(ANN), Interpreter.Bytes.of(OWNER)));
        var out = in.call("withdraw", vaultState(), List.of(BigInteger.ONE), both);
        assertEquals(BigInteger.valueOf(9_999_999), ((Interpreter.Rec) out.value()).fields().get(2));
    }
    /**
     * The integration of #80 with M2 (#84, #90): the strict shapes cover M2b's quorum and authorized and M2c's receives,
     * and a method that sends, mints, burns or deposits needs an audience as one that pays does (JSC0408), as the JavaScript
     * reader refuses them (semantics.test.js, the same cases).
     */
    @Test
    void theReader_takesM2sModifiersOnlyInTheCheckersShapes() throws Exception {
        String treasury = fixture("Treasury"), tips = fixture("Tips"), coins = fixture("Coins");
        for (String n : List.of("Treasury", "Council", "Tips", "Coins", "Dex", "Tipper")) IrJson.read(fixture(n));
        String quorum = "{ \"name\": \"quorum\", \"keys\": \"council\", \"threshold\": \"quorum\" }";
        refused(mods(treasury, "action", "pay", x -> x.replace("\"threshold\": \"quorum\"", "\"threshold\": \"params.guardian\"")),
                "both state fields or both params fields");
        refused(mods(treasury, "action", "pay", x -> x.replace("\"keys\": \"council\"", "\"keys\": \"quorum\"")), "quorum is of type Int, not List<Key>");
        refused(mods(treasury, "action", "pay", x -> x.replace("\"threshold\": \"quorum\"", "\"threshold\": \"quorum\", \"field\": \"council\"")),
                "a quorum names its keys and threshold, and no field");
        refused(mods(treasury, "action", "pay", x -> x.replace(", \"threshold\": \"quorum\"", "")), "a quorum names its keys and threshold");
        refused(mods(treasury, "action", "freeze", x -> x.replace("params.guardian", "council")), "council is of type List<Key>, not Credential");
        refused(mods(treasury, "action", "freeze", x -> x.replace("params.guardian", "params.nothing")), "the params have no field nothing");
        refused(mods(treasury, "action", "freeze", x -> x.replace("\"field\"", "\"keys\"")), "only a quorum names keys and a threshold");
        refused(mods(treasury, "init", "init", x -> "[{ \"name\": \"payable\" }, " + quorum + "]"), "an init takes only payable, not quorum");
        refused(mods(treasury, "action", "rotate", x -> "[" + quorum + ", { \"name\": \"batched\" }]"), "a batched action cannot use quorum");
        refused(mods(tips, "action", "credit", x -> x.substring(0, x.length() - 1) + ", { \"name\": \"batched\" }]"), "a batched action cannot use receives");
        refused(mods(tips, "destroy", "close", x -> x.substring(0, x.length() - 1) + ", { \"name\": \"receives\" }]"), "only an action receives deposits");
        refused(mods(tips, "action", "credit", x -> x.replace("\"name\": \"receives\"", "\"name\": \"receives\", \"field\": \"x\"")),
                "only receives names a type, and it names no field");
        refused(mods(coins, "action", "forge", x -> "[]"), "so it says who may call it");
        refused(mods(treasury, "action", "pay", x -> "[]"), "so it says who may call it");
    }
}
