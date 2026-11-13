package org.julclang.state.toolchain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D12 (r12): what may reach the script. Every {@code .jsc} 2 example is built by the toolchain (javac, the
 * processor, JuLC) as committed and under edits that change no behaviour, and its script hash must not move: blank
 * lines at several places, re-indentation, CRLF line ends, comments added and removed, the file renamed, and every
 * local, lambda and loop variable, {@code fn}, parameter and method renamed (the ABI's names included: no name reaches
 * the script). A real change, a different {@code require} condition, must move it. Review F4/Q2 and S5 on #66.
 */
class JscV2HashStabilityTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");
    static final String PKG = "org/julclang/state/examples/dsl/";

    static List<Path> examples() throws Exception {
        try (Stream<Path> s = Files.list(DSL)) {
            return s.filter(p -> p.toString().endsWith(".jsc")).filter(p -> {
                try {
                    return Files.readString(p).startsWith("jsc 2");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).sorted().toList();
        }
    }

    record Built(String hash, int size) {}

    static Built build(String file, String text) {
        var r = Toolchain.build(Map.of(PKG + file, text), Map.of());
        assertTrue(r.ok(), file + ": " + r.diagnostics());
        assertEquals(1, r.contracts().size());
        return new Built(r.contracts().get(0).scriptHash(), r.contracts().get(0).scriptSizeBytes());
    }

    // ------------------------------------------------------------------ edits, outside strings and comments

    /** The spans of code (not strings, not comments) of a source: [start, end) pairs. */
    static List<int[]> code(String s) {
        var out = new ArrayList<int[]>();
        int i = 0, start = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                out.add(new int[]{start, i});
                i++;
                while (s.charAt(i) != '"') i += s.charAt(i) == '\\' ? 2 : 1;
                start = ++i;
            } else if (s.startsWith("//", i)) {
                out.add(new int[]{start, i});
                while (i < s.length() && s.charAt(i) != '\n') i++;
                start = i;
            } else if (s.startsWith("/*", i)) {
                out.add(new int[]{start, i});
                i = s.indexOf("*/", i) + 2;
                start = i;
            } else {
                i++;
            }
        }
        out.add(new int[]{start, s.length()});
        return out;
    }

    /** Renames identifiers in code only (never inside a string or comment). */
    static String rename(String s, Map<String, String> names) {
        var out = new StringBuilder();
        int last = 0;
        for (int[] span : code(s)) {
            out.append(s, last, span[0]);
            Matcher m = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b").matcher(s.substring(span[0], span[1]));
            var sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(names.getOrDefault(m.group(), m.group())));
            m.appendTail(sb);
            out.append(sb);
            last = span[1];
        }
        out.append(s.substring(last));
        return out.toString();
    }

    static String stripComments(String s) {
        var out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (s.charAt(j) != '"') j += s.charAt(j) == '\\' ? 2 : 1;
                out.append(s, i, j + 1);
                i = j + 1;
            } else if (s.startsWith("//", i)) {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (s.startsWith("/*", i)) {
                i = s.indexOf("*/", i) + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString().replaceAll("(?m)[ \\t]+$", "");
    }

    /** Every name the file declares that is not also a field name (a field is the ABI's, and shorthand binds it). */
    static Map<String, String> renames(String s) {
        String code = stripComments(s).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
        Set<String> fields = new HashSet<>();
        // state { a: T, … }, type X = { a: T }, enum variants V { a: T }: their field names
        Matcher blocks = Pattern.compile("(state|=|[A-Z][A-Za-z0-9]*)\\s*\\{([^{}]*)\\}").matcher(code);
        while (blocks.find()) {
            Matcher f = Pattern.compile("([a-z][A-Za-z0-9_]*)\\s*:").matcher(blocks.group(2));
            while (f.find()) fields.add(f.group(1));
        }
        Set<String> declared = new java.util.TreeSet<>();
        for (String re : List.of("\\bconst\\s+([a-z][A-Za-z0-9_]*)", "\\bfn\\s+([a-z][A-Za-z0-9_]*)", "\\b(?:action|destroy|view)\\s+([a-z][A-Za-z0-9_]*)",
                "\\bfor\\s*\\(\\s*const\\s+([a-z][A-Za-z0-9_]*)", "\\b([a-z][A-Za-z0-9_]*)\\s*=>", "Some\\(([a-z][A-Za-z0-9_]*)\\)")) {
            Matcher m = Pattern.compile(re).matcher(code);
            while (m.find()) declared.add(m.group(1));
        }
        Matcher lambdas = Pattern.compile("\\(([a-z][A-Za-z0-9_]*(?:\\s*,\\s*[a-z][A-Za-z0-9_]*)+)\\)\\s*=>").matcher(code);
        while (lambdas.find()) for (String p : lambdas.group(1).split("\\s*,\\s*")) declared.add(p);
        // parameters: name(p: T, q: T) after fn/action/destroy/view/init
        Matcher sig = Pattern.compile("\\b(?:fn|action|destroy|view|init)\\s*[a-zA-Z0-9_]*\\s*\\(([^()]*)\\)").matcher(code);
        while (sig.find()) {
            Matcher p = Pattern.compile("([a-z][A-Za-z0-9_]*)\\s*:").matcher(sig.group(1));
            while (p.find()) declared.add(p.group(1));
        }
        declared.removeAll(fields);
        declared.removeAll(Set.of("caller", "received", "balance", "validFrom", "validTo", "state", "from", "of", "in"));
        declared.removeAll(LANGUAGE);
        var out = new LinkedHashMap<String, String>();
        for (String n : declared) out.put(n, n + "Rn");
        return out;
    }

    /** Words of the language (keywords, built-in values and types, operations, std and prelude names): never renamed. */
    static final Set<String> LANGUAGE = new HashSet<>(List.of("jsc", "package", "import", "from", "type", "enum", "contract", "state",
            "init", "action", "destroy", "view", "fn", "const", "return", "if", "else", "match", "for", "of", "in", "require", "pay", "only",
            "open", "payable", "after", "before", "true", "false", "None", "Some", "caller", "received", "balance", "validFrom", "validTo",
            "ada", "Int", "Bool", "Bytes", "Key", "Hash", "List", "Option", "Map", "BigMap", "State", "maxDatumBytes", "minAda",
            "maxValidityMs", "min", "max", "abs", "asKey", "asHash", "range", "length", "isEmpty", "head", "sum", "reverse", "contains",
            "any", "all", "find", "filter", "map", "fold", "concat", "take", "drop", "get", "has", "set", "delete", "size", "keys",
            "values", "empty", "_", "utf8",
            // M2: funds' fields, the members of Value and Address, a credential's patterns and their fields, the effects,
            // the prelude's constructors and the option assetSlots. A parameter may share a member's name (a Coins
            // parameter is called policy, as funds.policy is): such a name is not renamed, since a member is not.
            "funds", "held", "policy", "self", "lovelace", "covers", "payment", "none", "asset", "send", "mint", "burn", "accept",
            "keyCred", "scriptCred", "wallet", "script", "Value", "Address", "Credential", "KeyCred", "ScriptCred", "key", "hash",
            "assetSlots"));

    static {
        for (var b : org.julclang.state.dsl.v2.JscV2.bindings()) LANGUAGE.add(b.name());
    }

    /**
     * Every name the file declares (types, enums, variants, the contract, fields, fns, methods, parameters, consts, lambda,
     * loop and match variables), renamed so that their alphabetical order is reversed (design F1, F7: a suffix keeps the
     * order, this does not). Imported names and the language's words are kept.
     */
    static Map<String, String> renameAll(String s) {
        String code = stripComments(s).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
        var declared = new java.util.TreeSet<String>();
        for (String re : List.of("\\b(?:type|enum|contract)\\s+([A-Z]\\w*)", "\\b(?:fn|action|destroy|view|const)\\s+([a-z]\\w*)",
                "\\bfor\\s*\\(\\s*const\\s+([a-z]\\w*)", "\\b([a-z]\\w*)\\s*=>", "Some\\(([a-z]\\w*)\\)", "\\b([a-z]\\w*)\\s*:")) {
            Matcher m = Pattern.compile(re).matcher(code);
            while (m.find()) declared.add(m.group(1));
        }
        Matcher lambdas = Pattern.compile("\\(([a-z]\\w*(?:\\s*,\\s*[a-z]\\w*)+)\\)\\s*=>").matcher(code);
        while (lambdas.find()) declared.addAll(Arrays.asList(lambdas.group(1).split("\\s*,\\s*")));
        // Variants: capitalised names inside an enum's braces.
        Matcher enums = Pattern.compile("\\benum\\s+\\w+\\s*\\{").matcher(code);
        while (enums.find()) {
            int depth = 1, i = enums.end();
            var body = new StringBuilder();
            for (; i < code.length() && depth > 0; i++) {
                char c = code.charAt(i);
                if (c == '{') depth++;
                if (c == '}') depth--;
                if (depth == 1 && c != '}') body.append(c);
                else body.append(' ');
            }
            Matcher v = Pattern.compile("\\b([A-Z]\\w*)").matcher(body);
            while (v.find()) declared.add(v.group(1));
        }
        Matcher imports = Pattern.compile("import\\s*\\{([^}]*)\\}").matcher(code);
        while (imports.find()) for (String n : imports.group(1).split("\\s*,\\s*")) declared.remove(n.trim());
        declared.removeAll(LANGUAGE);
        var names = new ArrayList<>(declared);
        var out = new LinkedHashMap<String, String>();
        for (int i = 0; i < names.size(); i++) {
            int r = names.size() - i;   // reversed rank
            String code3 = "" + (char) ('a' + r / 676 % 26) + (char) ('a' + r / 26 % 26) + (char) ('a' + r % 26);
            String n = names.get(i);
            out.put(n, (Character.isUpperCase(n.charAt(0)) ? "Zq" : "zq") + code3);
        }
        return out;
    }

    static Map<String, String> edits(String src) {
        var e = new LinkedHashMap<String, String>();
        String nl = src;
        e.put("a blank line after package", nl.replaceFirst("(?m)^(package [^\\n]*\\n)", "$1\n"));
        e.put("a blank line after every '{'", nl.replaceAll("(?m)\\{[ \\t]*$", "{\n"));
        e.put("a blank line before the last '}'", nl.substring(0, nl.lastIndexOf('}')) + "\n" + nl.substring(nl.lastIndexOf('}')));
        e.put("re-indented", nl.replaceAll("(?m)^( +)", "$1$1"));
        e.put("CRLF line ends", nl.replace("\n", "\r\n"));
        e.put("comments added", nl.replaceFirst("(?m)^(package [^\\n]*\\n)", "$1/* a comment\n   over lines */\n")
                .replaceAll("(?m)\\{[ \\t]*$", "{ // opened"));
        e.put("comments removed", stripComments(nl));
        e.put("locals, lambda and loop variables, fns, parameters and methods renamed", rename(nl, renames(nl)));
        e.put("every declared name renamed (types, enums, variants, the contract, fields, fns, methods, parameters, locals), in"
                + " reversed alphabetical order", rename(nl, renameAll(nl)));
        return e;
    }

    @Test
    void behaviourNeutralEdits_leaveEveryScriptHash() throws Exception {
        var table = new StringBuilder();
        int checked = 0;
        for (Path f : examples()) {
            String name = f.getFileName().toString();
            String src = Files.readString(f);
            Built base = build(name, src);
            table.append(String.format("[hash] %-12s %s %,d B%n", name.replace(".jsc", ""), base.hash(), base.size()));
            for (var e : edits(src).entrySet()) {
                assertNotEquals(src, e.getValue(), name + ": the edit '" + e.getKey() + "' changed nothing");
                assertEquals(base.hash(), build(name, e.getValue()).hash(), name + ": " + e.getKey() + " moved the script hash");
                checked++;
            }
            assertEquals(base.hash(), build("Renamed" + name, src).hash(), name + ": renaming the file moved the script hash");
            checked++;
        }
        System.out.print(table);
        assertTrue(checked >= 9 * 10, "edits checked: " + checked);
    }

    /**
     * Design F1: the package is part of the contract's identity, recorded in the ABI manifest ({@code "contract"}), so a
     * package move is an ABI change the gate reports, and it may move the script hash: JuLC's output depends on the order
     * of the processor-generated validator's imports, and the contract's own import sorts by its package against the
     * fixed library imports (bloxbean/julc#328). Asserted as it is today: a package that keeps the
     * import's place keeps the hash, one that moves it may not; the ABI manifest changes either way.
     */
    @Test
    void aPackageMove_isAnAbiChange_andMayMoveTheHash() throws Exception {
        String src = Files.readString(DSL.resolve("Counter2.jsc"));
        Built base = build("Counter2.jsc", src);
        String home = "org.julclang.state.examples.dsl";
        var abi = new LinkedHashMap<String, String>();
        var hashes = new LinkedHashMap<String, String>();
        for (String pkg : List.of(home, "org.julclang.state.examples.moved", "org.other.pkg", "com.acme.app")) {
            String moved = src.replace("package " + home, "package " + pkg);
            var r = Toolchain.build(Map.of(pkg.replace('.', '/') + "/Counter2.jsc", moved), Map.of());
            assertTrue(r.ok(), pkg + ": " + r.diagnostics());
            abi.put(pkg, r.generated().get(0).abiJson());
            hashes.put(pkg, r.contracts().get(0).scriptHash());
            System.out.println("[hash] Counter2 in " + pkg + ": " + r.contracts().get(0).scriptHash());
        }
        assertEquals(base.hash(), hashes.get(home));
        // The contract's import keeps its place among the validator's imports: the hash stays.
        assertEquals(base.hash(), hashes.get("org.julclang.state.examples.moved"));
        // Every move is an ABI change: the manifest names the contract by its package.
        for (String pkg : List.of("org.julclang.state.examples.moved", "org.other.pkg", "com.acme.app")) {
            assertNotEquals(abi.get(home), abi.get(pkg), pkg);
            assertTrue(abi.get(pkg).contains("\"contract\": \"" + pkg + ".Counter2\""), pkg);
        }
        // Today org.other.pkg sorts after org.julclang.stdlib.*, so the import moves and the hash with it (F1's table).
        assertNotEquals(base.hash(), hashes.get("org.other.pkg"), "if JuLC no longer depends on import order, update D12 and J20");
    }

    @Test
    void aRealChange_movesTheScriptHash() throws Exception {
        for (Path f : examples()) {
            String name = f.getFileName().toString();
            String src = Files.readString(f);
            Matcher m = Pattern.compile("(?m)^(\\s*require\\([^\\n]*?) (>=|<=|>|<|==) ").matcher(src);
            if (!m.find()) continue;
            String flipped = Map.of(">=", ">", "<=", "<", ">", ">=", "<", "<=", "==", "!=").get(m.group(2));
            String changed = src.substring(0, m.start(2)) + flipped + src.substring(m.end(2));
            assertNotEquals(build(name, src).hash(), build(name, changed).hash(), name + ": " + m.group(2) + " → " + flipped);
        }
    }

    /**
     * Reordering fn declarations: no message depends on it (fns hold no generated key), and JuLC's output was measured
     * unchanged; D12 records that as a fact of JuLC, not a promise, and this test is the alarm if it stops holding.
     */
    @Test
    void reorderingFns_leavesPledgesHash() throws Exception {
        String src = Files.readString(DSL.resolve("Pledges.jsc"));
        Matcher m = Pattern.compile("(?m)^fn [^\\n]*\\n").matcher(src);
        var fns = new ArrayList<int[]>();
        while (m.find()) fns.add(new int[]{m.start(), m.end()});
        assertTrue(fns.size() >= 2, "Pledges declares two fns");
        String a = src.substring(fns.get(0)[0], fns.get(0)[1]), b = src.substring(fns.get(1)[0], fns.get(1)[1]);
        String swapped = src.substring(0, fns.get(0)[0]) + b + src.substring(fns.get(0)[1], fns.get(1)[0]) + a + src.substring(fns.get(1)[1]);
        boolean same = build("Pledges.jsc", src).hash().equals(build("Pledges.jsc", swapped).hash());
        System.out.println("[hash] Pledges with its two fns swapped: " + (same ? "unchanged" : "changed"));
        assertTrue(same, "swapping Pledges' fns moved its script hash");
    }
}
