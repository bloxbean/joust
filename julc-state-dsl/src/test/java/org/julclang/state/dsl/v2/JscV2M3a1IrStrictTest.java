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
