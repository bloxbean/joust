package org.julclang.state.toolchain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        var out = new LinkedHashMap<String, String>();
        for (String n : declared) out.put(n, n + "Rn");
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
        assertTrue(checked >= 8 * 9, "edits checked: " + checked);
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
