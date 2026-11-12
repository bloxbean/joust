package org.julclang.state.dsl.v2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review r2 F7: the TextMate grammar ({@code editors/vscode-jsc/syntaxes/jsc.tmLanguage.json}) names the words the front
 * ends know, and no others: the keywords both parsers accept, the built-in names, the prelude and every {@code std}
 * binding ({@link JscV2#bindings()}), so it does not drift as M2 adds to the std library.
 */
class JscTextMateTest {

    static final Path GRAMMAR = Path.of("../editors/vscode-jsc/syntaxes/jsc.tmLanguage.json");

    /** Words a parser compares tokens with: is("x"), at("x"), eat("x"), expect("x"), accept("x"). */
    static Set<String> parserWords(Path source) throws Exception {
        var out = new TreeSet<String>();
        var m = Pattern.compile("\\b(?:is|at|eat|expect|accept)\\(\"([A-Za-z_][A-Za-z0-9_]*)\"\\)").matcher(Files.readString(source));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** JavaScript habits the v2 parser recognises only to refuse them with a message (D12): not .jsc words. */
    static final Set<String> HABITS = Set.of("let", "var", "function", "class", "while", "do", "switch", "async", "await", "this", "in",
            "null", "undefined", "new");

    /** The words of the grammar's alternations, \b(a|b|c)\b. */
    static Set<String> grammarWords(String json) {
        var out = new TreeSet<String>();
        var alt = Pattern.compile("\\(((?:[A-Za-z_][A-Za-z0-9_]*\\|)+[A-Za-z_][A-Za-z0-9_]*)\\)").matcher(json);
        while (alt.find()) for (String w : alt.group(1).split("\\|")) out.add(w);
        var single = Pattern.compile("\\(([A-Za-z_][A-Za-z0-9_]*)\\)").matcher(json);
        while (single.find()) out.add(single.group(1));
        return out;
    }

    @Test
    void theGrammarNamesTheFrontEndsWords_andNoOthers() throws Exception {
        String json = Files.readString(GRAMMAR);
        Set<String> grammar = grammarWords(json);
        var known = new TreeSet<String>();
        var v2 = parserWords(Path.of("src/main/java/org/julclang/state/dsl/v2/Parser.java"));
        v2.removeAll(HABITS);
        var v1 = parserWords(Path.of("src/main/java/org/julclang/state/dsl/Parser.java"));
        var v1Keywords = Pattern.compile("KEYWORDS = Set\\.of\\(([^)]*)\\)").matcher(Files.readString(Path.of("src/main/java/org/julclang/state/dsl/Parser.java")));
        assertTrue(v1Keywords.find());
        for (String w : v1Keywords.group(1).replaceAll("[\"\\s]", "").split(",")) v1.add(w);
        var std = new TreeSet<String>();
        for (var b : JscV2.bindings()) std.add(b.name());
        for (var set : java.util.List.of(v2, v1, Checker.BUILTINS, Checker.PRELUDE, std)) known.addAll(set);
        // Forward: every word the front ends know is in the grammar (type names are matched by their own rule).
        var missing = new TreeSet<String>(known);
        missing.removeAll(grammar);
        missing.removeIf(w -> Character.isUpperCase(w.charAt(0)) && !Checker.PRELUDE.contains(w) && !w.equals("None"));
        assertEquals(Set.of(), missing, "words the front ends know that the TextMate grammar does not name");
        // Back: the grammar names nothing else (the std calls, builtins, keywords and modifiers alike); type names aside.
        var extra = new TreeSet<String>(grammar);
        extra.removeAll(known);
        extra.removeAll(Set.of("Int", "Bool", "Bytes", "Key", "Hash", "List", "Option", "Map", "BigMap", "Value", "Address", "None",
                "has", "isEmpty"));   // has and isEmpty: v1's calls (Lowering.FUNCTIONS)
        assertEquals(Set.of(), extra, "words the TextMate grammar names that no front end knows");
        System.out.println("[textmate] " + grammar.size() + " grammar words; " + std.size() + " std bindings, " + Checker.PRELUDE.size()
                + " prelude names, " + Checker.BUILTINS.size() + " built-ins, " + (v2.size() + v1.size()) + " parser words covered");
    }

    @Test
    void theLanguageConfigurationHasAWordPattern_andTheExtensionTargetsVsCode1109() throws Exception {
        String conf = Files.readString(Path.of("../editors/vscode-jsc/language-configuration.json"));
        assertTrue(conf.contains("\"wordPattern\""), conf);
        assertTrue(Files.readString(Path.of("../editors/vscode-jsc/package.json")).contains("\"vscode\": \"^1.109.0\""));
    }
}
