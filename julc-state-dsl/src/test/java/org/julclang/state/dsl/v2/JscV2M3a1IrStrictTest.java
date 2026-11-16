package org.julclang.state.dsl.v2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #101 review F2 (K2): the Java IR reader is as strict as the checker on owned programs. Each mutant of the bank port's
 * IR is a program the checker never produces, and the reader refuses it with the reason (as #98 F1's mutants). The
 * JavaScript reader's twin is {@code js/jsc-interpreter/test/owned-strict.test.js}, on the same mutants.
 */
class JscV2M3a1IrStrictTest {

    static final Path BANK = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Bank.jsc");

    @SuppressWarnings("unchecked")
    static Map<String, Object> ir() throws Exception {
        return (Map<String, Object>) AbiCheck.Json.parseStrict(JscV2.lower(Files.readString(BANK), "Bank.jsc").irJson());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> method(Map<String, Object> ir, String name) {
        for (Object m : (List<Object>) ir.get("methods")) if (name.equals(((Map<String, Object>) m).get("name"))) return (Map<String, Object>) m;
        throw new AssertionError("no method " + name);
    }

    @SuppressWarnings("unchecked")
    static List<Object> mods(Map<String, Object> m) {
        var l = new ArrayList<>((List<Object>) m.get("mods"));
        m.put("mods", l);
        return l;
    }

    static Map<String, Object> mod(String name, String field) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        if (field != null) m.put("field", field);
        return m;
    }

    /** A local of the given record type, a fresh id. */
    static Map<String, Object> local(int id, String name, String record) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("name", name);
        m.put("type", Map.of("record", record));
        m.put("taint", 0);
        m.put("at", List.of(1, 1));
        return m;
    }

    interface Mutant {
        void apply(Consumer<Map<String, Object>> mutate, String fragment) throws Exception;
    }

    static void reads(Consumer<Map<String, Object>> mutate) throws Exception {
        var ir = ir();
        mutate.accept(ir);
        IrJson.read(write(ir));
    }

    static void refused(Consumer<Map<String, Object>> mutate, String fragment) throws Exception {
        var ir = ir();
        mutate.accept(ir);
        String json = write(ir);
        var e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json), fragment);
        assertTrue(e.getMessage().contains(fragment), "'" + e.getMessage() + "' should mention '" + fragment + "'");
    }

    @Test
    void theUnmutatedPortReads() throws Exception {
        IrJson.read(write(ir()));
    }

    @Test
    void ownedMutants_areRefused() throws Exception {
        // A payable owned destroy (JSC0406 until M3a2; from M3a2 a program of that shape reads).
        if (IrJson.OWNED_STAGE < 2) refused(ir -> mods(method(ir, "close")).add(0, mod("payable", null)), "a payable owned destroy is not produced yet");
        else reads(ir -> mods(method(ir, "close")).add(0, mod("payable", null)));
        // receives on an owned action (decision A18: a deposit's target is an instance; JSC0447).
        refused(ir -> mods(method(ir, "deposit")).add(mod("receives", null)), "an owned method receives no deposits");
        // shared on the only shared-free destroy: no escape hatch is left (JSC1209).
        refused(ir -> method(ir, "close").put("shared", local(900, "bank", "State")), "has no escape hatch");
        // before on the only escape hatch: it could expire (JSC1209, #101 review S3).
        refused(ir -> mods(method(ir, "close")).add(mod("before", "saved")), "has no escape hatch");
        // into on an owned action, with no admits partner, and admits with no move (M3a3: refused until then; JSC1214 from it).
        boolean moves = IrJson.OWNED_STAGE >= 3;
        refused(ir -> method(ir, "withdraw").put("into", local(901, "dest", "State")),
                moves ? "needs an admits action for it" : "moves (into, admits) are not produced yet");
        if (!moves) refused(ir -> mods(method(ir, "setPaused")).add(mod("admits", "Account")), "moves (into, admits) are not produced yet");
        // a shared local that is not the instance's State.
        refused(ir -> method(ir, "deposit").put("shared", local(902, "bank", "Account")), "the shared state is a State");
        // native assets in an owned action (M3a2): a require reading funds.held (refused until M3a2; read from it).
        Mutant assets = IrJson.OWNED_STAGE < 2 ? JscV2M3a1IrStrictTest::refused : (m, f) -> reads(m);
        assets.apply(ir -> {
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) method(ir, "deposit").get("body");
            @SuppressWarnings("unchecked")
            var stmts = new ArrayList<>((List<Object>) body.get("stmts"));
            var held = new LinkedHashMap<String, Object>(Map.of("node", "FundsRef", "field", "held", "type", "Value", "taint", 0, "at", List.of(1, 1)));
            var cond = new LinkedHashMap<String, Object>(Map.of("node", "LedgerOp", "op", "covers", "args", List.of(held, held), "type", "Bool",
                    "taint", 0, "at", List.of(1, 1)));
            stmts.add(0, new LinkedHashMap<String, Object>(Map.of("stmt", "Require", "cond", cond, "message", "x", "at", List.of(1, 1))));
            body.put("stmts", stmts);
        }, "native assets in an owned method");
        // an owned type without an owned init (JSC1208).
        refused(ir -> {
            @SuppressWarnings("unchecked")
            var ms = new ArrayList<>((List<Object>) ir.get("methods"));
            ms.removeIf(m -> "open".equals(((Map<?, ?>) m).get("name")));
            ir.put("methods", ms);
        }, "has no owned init");
    }

    /** M3a3 (JSC1214): admits of a type with no move of it (Guild's transfer without its into) is refused. */
    @Test
    void aMoveAndItsAdmits_comeTogether() throws Exception {
        if (IrJson.OWNED_STAGE < 3) return;
        String guild = JscV2.lower(Files.readString(Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Guild.jsc")), "Guild.jsc").irJson();
        @SuppressWarnings("unchecked")
        var ir = (Map<String, Object>) AbiCheck.Json.parseStrict(guild);
        IrJson.read(write(ir));
        method(ir, "transfer").remove("into");
        var e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(write(ir)));
        assertTrue(e.getMessage().contains("but no owned action moves"), e.getMessage());
    }

    static final String DSL = "../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/";

    @SuppressWarnings("unchecked")
    static Map<String, Object> lowered(String contract) throws Exception {
        return (Map<String, Object>) AbiCheck.Json.parseStrict(JscV2.lower(Files.readString(Path.of(DSL + contract + ".jsc")), contract + ".jsc").irJson());
    }

    /** A mutant of {@code contract}'s IR refused with {@code fragment}. */
    static void refusedIn(String contract, Consumer<Map<String, Object>> mutate, String fragment) throws Exception {
        var ir = lowered(contract);
        mutate.accept(ir);
        String json = write(ir);
        var e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json), fragment);
        assertTrue(e.getMessage().contains(fragment), "'" + e.getMessage() + "' should mention '" + fragment + "'");
    }

    /** Pass (Ferry's owned type) gains a BigMap field, with an empty map in each New Pass and the arriving local's taints. */
    @SuppressWarnings("unchecked")
    static void passKeepsAMap(Map<String, Object> ir) {
        var map = Map.of("bigmap", List.of("Bytes", "Int"));
        for (Object r : (List<Object>) ir.get("records")) {
            var rm = (Map<String, Object>) r;
            if (rm.get("name").equals("Pass")) ((List<Object>) rm.get("fields")).add(new LinkedHashMap<String, Object>(Map.of("name", "m", "type", map)));
        }
        growRecords(ir.get("methods"), "Pass", map);
        var arriving = (Map<String, Object>) method(ir, "board").get("arriving");
        if (arriving.get("fieldTaints") instanceof Map<?, ?> ft) ((Map<String, Object>) ft).put("m", 0);
    }

    /** Each {@code New record} in raw IR gains an empty map for a field added after the others. */
    @SuppressWarnings("unchecked")
    static void growRecords(Object o, String record, Object mapType) {
        if (o instanceof Map<?, ?> m) {
            var mm = (Map<String, Object>) m;
            if ("New".equals(mm.get("node")) && record.equals(mm.get("record"))) {
                var values = new ArrayList<>((List<Object>) mm.get("values"));
                values.add(new LinkedHashMap<String, Object>(Map.of("node", "BigEmpty", "type", mapType, "taint", 0, "at", mm.get("at"))));
                mm.put("values", values);
            }
            for (Object v : new ArrayList<>(mm.values())) growRecords(v, record, mapType);
        } else if (o instanceof List<?> l) {
            for (Object v : l) growRecords(v, record, mapType);
        }
    }

    /**
     * #107 round-r2 review C5, F6, F7: the shapes stage 3 admits are the checker's. Each mutant of Ferry's (or Armory's)
     * IR is one the checker never produces: an admission without its Bytes id first, or batched; an arriving object, a
     * move or an invariant of a type that keeps a BigMap; a move whose destination is not a State.
     */
    @Test
    @SuppressWarnings("unchecked")
    void stage3_admitsOnlyTheCheckersMoveShapes() throws Exception {
        if (IrJson.OWNED_STAGE < 3) return;
        IrJson.read(write(lowered("Ferry")));
        IrJson.read(write(lowered("Armory")));
        String id = "an admits action's first parameter is the arriving object's id, a Bytes (JSC1212)";
        refusedIn("Ferry", ir -> {
            method(ir, "board").put("params", new ArrayList<>());
            method(ir, "board").put("checked", new ArrayList<>());
        }, id);
        refusedIn("Ferry", ir -> ((Map<String, Object>) ((List<Object>) method(ir, "board").get("params")).get(0)).put("type", "Int"), id);
        refusedIn("Ferry", ir -> mods(method(ir, "board")).add(mod("batched", null)), "an admits action cannot be batched (JSC1212)");
        refusedIn("Ferry", ir -> ((Map<String, Object>) method(ir, "lift").get("into")).put("type", Map.of("record", "Pass")),
                "a move's destination is a State");
        refusedIn("Ferry", JscV2M3a1IrStrictTest::passKeepsAMap, "Pass keeps a BigMap, so it cannot move yet (JSC1212)");
        refusedIn("Ferry", ir -> {   // the admission first, so its arriving parameter is read before the moves
            passKeepsAMap(ir);
            var ms = (List<Object>) ir.get("methods");
            var board = method(ir, "board");
            ms.remove(board);
            ms.add(0, board);
        }, "the arriving Pass keeps a BigMap, so an admits action cannot take it as a parameter (JSC1212)");
        refusedIn("Armory", ir -> {
            var lit = new LinkedHashMap<String, Object>(Map.of("node", "Lit", "value", true, "type", "Bool", "taint", 0, "at", List.of(1, 1)));
            var body = new LinkedHashMap<String, Object>(Map.of("stmts", List.of(), "result", lit, "at", List.of(1, 1)));
            ir.put("invariants", new ArrayList<>(List.of(new LinkedHashMap<String, Object>(Map.of("name", "small", "on", "Vault", "body", body,
                    "at", List.of(1, 1))))));
        }, "the owned type Vault keeps a BigMap, so it has no invariants (JSC1213)");
    }

    /** A deep copy of parsed JSON (maps and lists), so a mutant can copy a part of the program. */
    @SuppressWarnings("unchecked")
    static Object copy(Object o) {
        if (o instanceof Map<?, ?> m) {
            var c = new LinkedHashMap<String, Object>();
            for (var e : ((Map<String, Object>) m).entrySet()) c.put(e.getKey(), copy(e.getValue()));
            return c;
        }
        if (o instanceof List<?> l) {
            var c = new ArrayList<Object>();
            for (Object v : l) c.add(copy(v));
            return c;
        }
        return o;
    }

    /** A copy of a method's JSON with its locals renumbered (ids, references, checked inputs), to sit beside the original. */
    @SuppressWarnings("unchecked")
    static Object renumber(Object o, int by) {
        if (o instanceof Map<?, ?> m) {
            var c = new LinkedHashMap<String, Object>();
            for (var e : ((Map<String, Object>) m).entrySet()) {
                Object v = e.getValue();
                if (e.getKey().equals("id") && v instanceof Number n) c.put("id", n.longValue() + by);
                else if (e.getKey().equals("local") && "LocalRef".equals(m.get("node")) && v instanceof Number n) c.put("local", n.longValue() + by);
                else if (e.getKey().equals("checked") && v instanceof List<?> l) c.put("checked", l.stream().map(x -> ((Number) x).longValue() + by).toList());
                else c.put(e.getKey(), renumber(v, by));
            }
            return c;
        }
        if (o instanceof List<?> l) return new ArrayList<>(l.stream().map(v -> renumber(v, by)).toList());
        return o;
    }

    /** Each {@code New Account} in raw IR gains an empty map for a field added after the others. */
    @SuppressWarnings("unchecked")
    static void growAccounts(Object o, Object mapType) {
        if (o instanceof Map<?, ?> m) {
            var mm = (Map<String, Object>) m;
            if ("New".equals(mm.get("node")) && "Account".equals(mm.get("record"))) {
                var values = new ArrayList<>((List<Object>) mm.get("values"));
                values.add(new LinkedHashMap<String, Object>(Map.of("node", "BigEmpty", "type", mapType, "taint", 0, "at", mm.get("at"))));
                mm.put("values", values);
            }
            for (Object v : new ArrayList<>(mm.values())) growAccounts(v, mapType);
        } else if (o instanceof List<?> l) {
            for (Object v : l) growAccounts(v, mapType);
        }
    }

    /** Replaces each {@code balance} CtxRef in raw IR with the Int literal 1. */
    @SuppressWarnings("unchecked")
    static void balanceToOne(Object o) {
        if (o instanceof Map<?, ?> m) {
            var mm = (Map<String, Object>) m;
            for (var e : mm.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> v && "CtxRef".equals(v.get("node")) && "balance".equals(v.get("name"))) {
                    var lit = new LinkedHashMap<String, Object>();
                    lit.put("node", "Lit");
                    lit.put("value", "1");
                    lit.put("type", "Int");
                    lit.put("taint", 0);
                    lit.put("at", v.get("at"));
                    e.setValue(lit);
                } else {
                    balanceToOne(e.getValue());
                }
            }
        } else if (o instanceof List<?> l) {
            for (Object v : l) balanceToOne(v);
        }
    }

    /**
     * #101 round-r2 review C3, C4, C5: M3a2's owned-type forms (a second owned type with its own owned init and escape
     * hatch, so JSC1208 and JSC1209 cannot mask the stage; a BigMap in an owned type), an escape hatch that never reads
     * balance (JSC1216), and present nulls for the optional owned fields.
     */
    @Test
    @SuppressWarnings("unchecked")
    void reviewRound2_mutants_areRefused() throws Exception {
        {   // M3a2's forms: refused while the readers' stage is 1 (complete programs, refused by the stage alone), read from M3a2
            Mutant m3a2 = IrJson.OWNED_STAGE < 2 ? JscV2M3a1IrStrictTest::refused : (m, f) -> reads(m);
            m3a2.apply(ir -> {
                var records = (List<Object>) ir.get("records");
                var second = (Map<String, Object>) copy(records.get(0));
                second.put("name", "Account2");
                records.add(second);
                ir.put("owned", new ArrayList<>(List.of("Account", "Account2")));
                var ms = (List<Object>) ir.get("methods");
                for (String n : List.of("open", "close")) {
                    var m = (Map<String, Object>) renumber(method(ir, n), 1000);
                    m.put("name", n + "2");
                    m.put("on", "Account2");
                    ms.add(m);
                }
            }, "a second owned type (Account2) is not produced yet (M3a2; JSC1215)");
            m3a2.apply(ir -> {
                var account = (Map<String, Object>) ((List<Object>) ir.get("records")).get(0);
                var fields = (List<Object>) account.get("fields");
                var map = Map.of("bigmap", List.of("Key", "Int"));
                fields.add(new LinkedHashMap<String, Object>(Map.of("name", "entries", "type", map)));
                growAccounts(ir.get("methods"), map);
            }, "a BigMap in the owned type Account is not produced yet (M3a2; JSC1215)");
        }
        // The only escape hatch pays 1, not balance: no object could ever be closed (JSC1216).
        refused(ir -> balanceToOne(method(ir, "close").get("body")), "it is its type's escape hatch but never reads balance (JSC1216)");
        // Present nulls: malformed, not absent.
        for (String k : List.of("into", "arriving", "shared", "on", "reads")) {
            refused(ir -> method(ir, "withdraw").put(k, null), "\"" + k + "\" is null: an absent field is not written");
        }
    }

    // ------------------------------------------------------------------ a minimal JSON writer (the parser's inverse)

    static String write(Object o) {
        var sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static void write(Object o, StringBuilder sb) {
        switch (o) {
            case null -> sb.append("null");
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (var e : ((Map<String, Object>) m).entrySet()) {
                    if (!first) sb.append(", ");
                    first = false;
                    write(e.getKey(), sb);
                    sb.append(": ");
                    write(e.getValue(), sb);
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int k = 0; k < l.size(); k++) {
                    if (k > 0) sb.append(", ");
                    write(l.get(k), sb);
                }
                sb.append(']');
            }
            case String s -> {
                sb.append('"');
                for (char c : s.toCharArray()) {
                    switch (c) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\n' -> sb.append("\\n");
                        default -> {
                            if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                            else sb.append(c);
                        }
                    }
                }
                sb.append('"');
            }
            default -> sb.append(o);
        }
    }
}
