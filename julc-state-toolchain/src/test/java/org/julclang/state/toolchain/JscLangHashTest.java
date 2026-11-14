package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.dsl.v2.JscLang;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0021 L12 (M5b), the hash fixtures in both classes (D12). Hash-preserving: every rename the language server makes
 * (planned from the model's references as the server plans it: the declaration and every reference, a shorthand keeping
 * its other meaning) of a local, parameter, lambda or loop variable, match binding or fn, and every import-adding edit,
 * leave the script hash of a file that builds before and after unchanged. Code-changing: the deliberate completion of
 * D12 (replacing {@code count} with {@code by} in {@code count + by} in {@code Counter2.increment}) changes it, which L12
 * allows.
 */
class JscLangHashTest {

    /** A 0-based [line, col, endCol] occurrence and its replacement. */
    record Edit(int line, int col, int endCol, String text) {}

    static String apply(String src, List<Edit> edits) {
        String[] lines = src.split("\n", -1);
        var sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(Edit::line).thenComparing(Comparator.comparingInt(Edit::col).reversed()));
        for (Edit e : sorted) {
            String l = lines[e.line()];
            lines[e.line()] = l.substring(0, e.col()) + e.text() + l.substring(e.endCol());
        }
        return String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    static int num(Object o) {
        return ((Number) o).intValue();
    }

    /**
     * The rename of declaration {@code id} to {@code to}, as the server plans it (features.js renamePlan and occurrences):
     * the declaration and every reference; a shorthand keeps its other meaning (`{ field: value }`: a field renamed is
     * `to: from`, a value `from: to`), and one whose two meanings are both the declaration is one edit (C5 on #99).
     */
    @SuppressWarnings("unchecked")
    static List<Edit> rename(Map<String, Object> model, int id, String from, String to) {
        var byRange = new java.util.LinkedHashMap<String, Edit>();
        var count = new java.util.HashMap<String, Integer>();
        String kind = null;
        for (Object o : list(model.get("declarations"))) {
            var d = (Map<String, Object>) o;
            if (num(d.get("id")) == id) kind = (String) d.get("kind");
        }
        boolean fieldish = "field".equals(kind) || "stateField".equals(kind);
        for (Object o : list(model.get("declarations"))) {
            var d = (Map<String, Object>) o;
            if (num(d.get("id")) != id) continue;
            var r = list(d.get("nameRange"));
            String key = r.get(0) + ":" + r.get(1) + ":" + r.get(3);
            String text = Boolean.TRUE.equals(d.get("shorthand")) ? (fieldish ? to + ": " + from : from + ": " + to) : to;
            byRange.put(key, new Edit(num(r.get(0)), num(r.get(1)), num(r.get(3)), text));
            count.merge(key, 1, Integer::sum);
        }
        for (Object o : list(model.get("references"))) {
            var r = list(o);
            if (num(r.get(4)) != id) continue;
            boolean shorthand = (num(r.get(5)) & 1) == 1;
            String key = r.get(0) + ":" + r.get(1) + ":" + r.get(3);
            count.merge(key, 1, Integer::sum);
            String text = count.get(key) > 1 ? to : shorthand ? (fieldish ? to + ": " + from : from + ": " + to) : to;
            byRange.put(key, new Edit(num(r.get(0)), num(r.get(1)), num(r.get(3)), text));
        }
        return new ArrayList<>(byRange.values());
    }

    static String hash(String file, String text) throws Exception {
        return JscV2HashStabilityTest.build(file, text).hash();
    }

    @Test
    @SuppressWarnings("unchecked")
    void renamesAndImportAddingEditsKeepTheScriptHash() throws Exception {
        int checked = 0;
        for (String name : List.of("Counter2.jsc", "Escrow.jsc", "Crowdfund.jsc", "Pledges.jsc")) {
            String src = Files.readString(JscV2HashStabilityTest.DSL.resolve(name));
            String base = hash(name, src);
            var model = (Map<String, Object>) AbiCheck.Json.parse(JscLang.analyzeJson(src, name));
            int per = 0;
            for (Object o : list(model.get("declarations"))) {
                var d = (Map<String, Object>) o;
                String kind = (String) d.get("kind");
                if (!List.of("const", "param", "lambdaParam", "loopVar", "binding", "fn").contains(kind) || per >= 8) continue;
                // A parameter of an action or destroy is a redeemer field (the ABI changes; the script does not): kept in.
                String from = (String) d.get("name");
                String renamed = apply(src, rename(model, num(d.get("id")), from, from + "Z"));
                assertNotEquals(src, renamed);
                var after = JscLang.analyze(renamed, name);
                assertTrue(after.accepted(), name + ": renaming " + kind + " " + from + ": " + after.diagnostics());
                assertEquals(base, hash(name, renamed), name + ": renaming " + kind + " " + from + " moved the script hash");
                per++;
                checked++;
            }
        }
        // Import-adding edits (joust.addImport and the JSC0704 fix): a new import line, and a name added to an import.
        String counter = Files.readString(JscV2HashStabilityTest.DSL.resolve("Counter2.jsc"));
        String base = hash("Counter2.jsc", counter);
        String withImport = counter.replaceFirst("\n\n", "\nimport { sha2_256 } from \"std/crypto\"\n\n");
        assertNotEquals(counter, withImport);
        assertEquals(base, hash("Counter2.jsc", withImport), "a new import line moved the script hash");
        String withTwo = withImport.replace("import { sha2_256 }", "import { sha2_256, blake2b_256 }");
        assertEquals(base, hash("Counter2.jsc", withTwo), "a name added to an import moved the script hash");
        checked += 2;
        System.out.println("[hash] L12: " + checked + " hash-preserving edits keep the script hash");
        assertTrue(checked >= 20, checked + " edits");
    }

    /** features.js RENAMABLE. */
    static final List<String> RENAMABLE = List.of("param", "const", "loopVar", "binding", "lambdaParam", "fn", "type", "enum", "variant", "field",
            "stateField", "action", "destroy", "view", "event");

    /**
     * F8, C3 on #99: a rename of every renamable kind keeps the script hash (L12), and changes the ABI manifest exactly
     * when the model marks the declaration {@code abi} (the rename warns exactly then: D7).
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyRenamableKindKeepsTheHashAndChangesTheManifestExactlyWhenAbi() throws Exception {
        var kinds = new java.util.TreeMap<String, Integer>();
        var abiKinds = new java.util.TreeSet<String>();
        for (String name : List.of("Counter2.jsc", "Escrow.jsc", "Crowdfund.jsc", "Pledges.jsc", "Treasury.jsc", "Council.jsc", "Tipper.jsc",
                "Inbox.jsc")) {
            String src = Files.readString(JscV2HashStabilityTest.DSL.resolve(name));
            var base = JscV2HashStabilityTest.build(name, src);
            var model = (Map<String, Object>) AbiCheck.Json.parse(JscLang.analyzeJson(src, name));
            var per = new java.util.HashMap<String, Integer>();
            for (Object o : list(model.get("declarations"))) {
                var d = (Map<String, Object>) o;
                String kind = (String) d.get("kind");
                if (!RENAMABLE.contains(kind) || per.getOrDefault(kind, 0) >= 2 || d.get("name") == null) continue;
                String from = (String) d.get("name");
                String renamed = apply(src, rename(model, num(d.get("id")), from, from + "Z"));
                var after = JscLang.analyze(renamed, name);
                if (!after.accepted()) continue;   // e.g. a name another file of the build imports: the server refuses (F2)
                var built = JscV2HashStabilityTest.build(name, renamed);
                assertEquals(base.hash(), built.hash(), name + ": renaming " + kind + " " + from + " moved the script hash");
                boolean abi = Boolean.TRUE.equals(d.get("abi"));
                boolean changed = !java.util.Objects.equals(base.abi(), built.abi());
                assertEquals(abi, changed, name + ": renaming " + kind + " " + from + (changed ? " changed" : " kept") + " the manifest, abi " + abi);
                if (abi) abiKinds.add(kind);
                per.merge(kind, 1, Integer::sum);
                kinds.merge(kind, 1, Integer::sum);
            }
        }
        System.out.println("[hash] F8: renames by kind " + kinds + "; the manifest changes for " + abiKinds);
        for (String k : RENAMABLE) assertTrue(kinds.containsKey(k), "no " + k + " renamed: " + kinds);
    }

    @Test
    void theDeliberateCodeChangingCompletionChangesIt() throws Exception {
        String counter = Files.readString(JscV2HashStabilityTest.DSL.resolve("Counter2.jsc"));
        assertTrue(counter.contains("count + by"), "Counter2.increment's count + by");
        String completed = counter.replaceFirst("count \\+ by", "by + by");
        assertNotEquals(hash("Counter2.jsc", counter), hash("Counter2.jsc", completed), "a code change is allowed to change the program");
    }
}
