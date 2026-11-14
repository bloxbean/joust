package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code .jsc} front end (ADR-0018): files that start with {@code jsc 1}, or with no header. Parse ({@link Parser}), check into the
 * typed IR ({@link Checker}), then lower it to Java ({@link JavaEmitter}) or evaluate it ({@link Interpreter}).
 */
public final class JscV2 {
    private JscV2() {}

    /** A located warning with its stable code: line and column in the {@code .jsc} file (D12, F13). */
    public record Warning(int line, int col, String code, String message) {
        /** {@code file:line:col: warning[CODE]: message}. */
        public String format(String file) {
            return file + ":" + line + ":" + col + ": warning[" + code + "]: " + message;
        }
    }

    /**
     * Generated Java, warnings, the functions that call themselves (reported, J9), the path of the Java file
     * ({@code a/b/Name.java}), its source map (D12: JSON, generated line to {@code .jsc} line and column), the ABI
     * manifest (D12, J17: JSON, committed as {@code Name.abi.json}) and the checked IR (D15, M1b: JSON, written as
     * {@code Name.ir.json} beside the Java, for the JavaScript reference interpreter; {@code docs/reference/jsc-2-ir.md}),
     * and the sites of the generated checks (D12, M1e: each on-chain message key with its {@code .jsc} position).
     */
    public record Lowered(String java, List<Warning> diagnostics, Set<String> recursiveFns, String fileName, String javaPath,
                          String sourceMapJson, String abiJson, String irJson, List<Site> messages, List<LibraryUse> libraries) {
        /** For each generated Java line (1-based) with a source: {@code [javaLine, jscLine, jscCol]}. */
        public List<int[]> sourceMap() {
            return Abi.sourceMap(java, fileName);
        }

        /**
         * Where a traced message comes from (D12): {@code File.jsc:line:col} with what raised it. A message matches a trace
         * that is exactly it, or that ends with {@code ": "} and it (a runtime prefixes its own context); of the messages
         * that match, only the longest counts, so an author's whole message wins over an operation's that it ends with,
         * and {@code #pay:spend0.1} never matches {@code #pay:spend0.10}. The same rule as {@code @julclang/joust-runtime}'s
         * {@code messageSites} (review r1 C3, S1, S2). A generated key has one site; an author's or an operation's message
         * may have several. Empty when no message matches.
         */
        public List<String> explain(String trace) {
            var out = new java.util.ArrayList<String>();
            String best = matching(trace, messages.stream().map(Site::message).toList());
            if (best == null) return out;
            for (Site s : messages) {
                if (s.message().equals(best)) out.add((s.file() == null ? fileName : s.file()) + ":" + s.line() + ":" + s.col() + " (" + s.describe() + ")");
            }
            return out;
        }

        /** The warnings, each formatted {@code file:line:col: warning: message}. */
        public List<String> warnings() {
            return diagnostics.stream().map(w -> w.format(fileName)).toList();
        }
    }

    /**
     * A library a lowering used (ADR-0018 D9, J12), for {@code joust.lock} and the build report: {@code kind} {@code jsc}
     * or {@code java}; {@code name} the module id of a {@code .jsc} library ({@code app/fees.jsc}, or
     * {@code pkg:@acme/fees/fees.jsc} from an npm package) or {@code java:<class>}; the SHA-256 of its on-chain sources
     * (a {@code .jsc} library's text; a Java library's certified sources, {@code JavaLibraryCertifier.hash}).
     */
    public record LibraryUse(String kind, String name, String sha256) {
        /**
         * The kind of a Java library a hand-written Java contract imports (review r2, #82 C7): pinned as a {@code java:}
         * import is, under the same name, though nothing certifies it for {@code .jsc}.
         */
        public static final String JAVA_SOURCE = "java-source";

        /** Whether it comes from outside the project's sources (an npm package, a Java library): what joust.lock pins. */
        public boolean external() {
            return kind.equals("java") || kind.equals(JAVA_SOURCE) || name.startsWith(JscModules.PACKAGE);
        }
    }

    /**
     * A message the script can trace and the {@code .jsc} construct that raises it (D12): {@code kind} is {@code input}
     * (a checked parameter), {@code pay} (a payout's sign), {@code require} (the author's) or {@code operation} (a std or
     * collection operation's domain); {@code in} the method or fn; {@code what} the parameter of an input check.
     */
    public record Site(String message, String kind, int line, int col, String in, String what, String file) {
        /** A site in the lowered file itself ({@code file} null; an imported library's site names its file: D9, M4). */
        public Site(String message, String kind, int line, int col, String in, String what) {
            this(message, kind, line, col, in, what, null);
        }

        String describe() {
            return switch (kind) {
                case "input" -> "the parameter " + what + " of " + in + " is not a well-formed input";
                case "pay" -> "a payout in " + in + " is negative";
                case "require" -> "a require in " + in;
                default -> "in " + in;
            };
        }
    }

    /**
     * The message a trace raised, of {@code messages}: the longest that the trace is, or ends with after {@code ": "};
     * null when none. D12's matching rule, shared with the JS runtime.
     */
    public static String matching(String trace, java.util.Collection<String> messages) {
        if (trace == null) return null;
        String best = null;
        for (String m : messages) {
            if (m.isEmpty() || !(trace.equals(m) || trace.endsWith(": " + m))) continue;
            if (best == null || m.length() > best.length()) best = m;
        }
        return best;
    }

    /** Words the {@code .jsc} 2 parser reads as syntax wherever an expression starts (JavaScript habits included). */
    private static final Set<String> EXPRESSION_WORDS = Set.of("match", "undefined", "null", "async", "await", "function", "this", "new",
            "class", "switch", "if", "true", "false");

    /**
     * Whether {@code n} cannot be a name in {@code .jsc} 2: a reserved word of the checker, a word the parser reads as
     * syntax ({@code match}, {@code undefined}, …), or a method modifier ({@code only}, {@code batched}, …; as a field or
     * binding name, JSC0211).
     */
    public static boolean isReservedName(String n) {
        // The modifier words (M1c, JSC0211) are no field or binding name either (#76 review F6).
        return Checker.RESERVED.contains(n) || EXPRESSION_WORDS.contains(n) || Parser.MODIFIERS.contains(n);
    }

    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * The file name a lowering embeds (in comments and require messages) must be a bare name of letters, digits,
     * {@code .}, {@code _} and {@code -}: anything else could close a Java comment or string and change the contract
     * (security S3). Checked at every boundary: {@link #lower}, {@code JscMain} and the toolchain.
     */
    public static void checkFileName(String fileName) {
        if (fileName == null || !SAFE_FILE_NAME.matcher(fileName).matches()) {
            throw new JscException(1, 1, "JSC0001", "the file name " + (fileName == null ? "(none)" : "'" + printable(fileName) + "'")
                    + " may contain only ASCII letters and digits, '.', '_' and '-', and must start with a letter or digit (it is embedded in the generated Java)");
        }
    }

    private static String printable(String s) {
        var out = new StringBuilder();
        for (char c : s.toCharArray()) out.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        return out.toString();
    }

    /** A Java string literal for any text (quotes, backslashes and control characters escaped). */
    public static String javaString(String text) {
        return JavaEmitter.str(text);
    }

    /**
     * Checks and lowers a {@code .jsc} 2 source to a Java {@code @StateContract}, on its own: it imports no libraries and
     * no other contract.
     */
    public static Lowered lower(String source, String fileName) {
        return lower(source, fileName, null);
    }

    /**
     * {@link #lower}, recording the language core's spans into {@code spans} when it is not null (ADR-0021 D6): the same
     * steps, so the lowering and every error are the same with or without (L5).
     */
    static Lowered lower(String source, String fileName, Spans spans) {
        return lowered(check(source, fileName, spans), source, fileName);
    }

    /**
     * Checks and lowers {@code source}, the module {@code id} of a build whose other {@code .jsc} files {@code modules}
     * finds (ADR-0018 D9): its {@code .jsc} library imports (M4) and its {@code import contract { C } from "./C.jsc"}
     * imports (M2c, each contract checked on its own) resolve there. A contract lowers to its {@code @StateContract}, a
     * library ({@code library <package>}) to its {@code @OnchainLibrary} class, which Java contracts can call too. A
     * library has no ABI manifest and no IR export of its own: each contract that imports it carries its declarations (the
     * IR's {@code libraries}).
     */
    public static Lowered lower(String source, String fileName, String id, JscModules modules) {
        return lower(source, fileName, id, modules, JavaLibraries.NONE);
    }

    /** As above, with the certified Java libraries its {@code java:} imports may name (D9, M4b). */
    public static Lowered lower(String source, String fileName, String id, JscModules modules, JavaLibraries javaLibraries) {
        return lower(source, fileName, id, modules, javaLibraries, null);
    }

    /** As above, recording the language core's spans of {@code source} (ADR-0021 D6; the integration with M2/M4). */
    static Lowered lower(String source, String fileName, String id, JscModules modules, JavaLibraries javaLibraries, Spans spans) {
        return lowered(check(source, fileName, id, modules, javaLibraries, spans), source, fileName);
    }

    /** Whether a {@code .jsc} 2 source is a library ({@code library <package>}): read from its header only. */
    public static boolean isLibrary(String source) {
        return Parser.header(source).library();
    }

    private static Lowered lowered(Ir.Program p, String source, String fileName) {
        var warnings = p.warnings().stream().map(w -> new Warning(w.line(), w.col(), w.code(), w.message())).toList();
        JavaEmitter.Emitted e = JavaEmitter.emit(p);
        String javaPath = p.pkg().replace('.', '/') + "/" + p.name() + ".java";
        var sites = new java.util.ArrayList<Site>();
        for (JavaEmitter.Check c : e.checks()) sites.add(new Site(c.message(), c.kind(), c.pos().line(), c.pos().col(), c.in(), c.what(), c.file()));
        var uses = new java.util.ArrayList<LibraryUse>();
        for (Ir.Library l : p.libraries()) uses.add(new LibraryUse("jsc", l.id() != null ? l.id() : l.qualified(), l.sourceSha256()));
        for (Ir.JavaLib j : p.javaLibraries()) uses.add(new LibraryUse("java", "java:" + j.fqcn(), j.sourceSha256()));
        return new Lowered(e.java(), warnings, p.recursiveFns(), fileName, javaPath, Abi.sourceMapJson(e.java(), fileName, javaPath, e.checks()),
                p.library() ? null : Abi.of(p), p.library() ? null : IrJson.write(p, IrJson.sourceHash(source)), List.copyOf(sites),
                List.copyOf(uses));
    }

    /**
     * The front end alone: the file name's check, parse and check (with the language core's spans when {@code spans} is
     * not null). Every coded diagnostic of {@link #lower} comes from here: the emitter, the ABI manifest and the IR
     * export raise only internal errors (JSC0005 on the build paths).
     */
    static Ir.Program check(String source, String fileName, Spans spans) {
        checkFileName(fileName);
        return Checker.check(Parser.parse(source, spans), fileName);
    }

    /**
     * The front end alone for the module {@code id} of a build (its library and contract imports resolved in
     * {@code modules}), with the language core's spans of {@code source} when {@code spans} is not null: the language
     * server's analysis of a file of a project (ADR-0021 L1, with M2c's and M4's imports).
     */
    static Ir.Program check(String source, String fileName, String id, JscModules modules, JavaLibraries javaLibraries, Spans spans) {
        checkFileName(fileName);
        return Checker.check(source, fileName, id, modules, javaLibraries, spans);
    }

    /** The reference interpreter (D15) of a {@code .jsc} 2 source, over the same checked IR the lowering reads. */
    public static Interpreter interpreter(String source, String fileName, Interpreter.Hashes hashes) {
        return interpreter(source, fileName, null, JscModules.NONE, hashes);
    }

    /**
     * The reference interpreter of the module {@code id} of a build, with the {@code .jsc} libraries (M4) and the contracts
     * (M2c) it imports.
     */
    public static Interpreter interpreter(String source, String fileName, String id, JscModules modules, Interpreter.Hashes hashes) {
        return interpreter(source, fileName, id, modules, JavaLibraries.NONE, hashes);
    }

    /** As above, with Java libraries: a call of one has no answer in the interpreter (M4b). */
    public static Interpreter interpreter(String source, String fileName, String id, JscModules modules, JavaLibraries javaLibraries,
                                          Interpreter.Hashes hashes) {
        checkFileName(fileName);
        return new Interpreter(Checker.check(source, fileName, id, modules, javaLibraries), hashes);
    }

    /**
     * A {@code std} binding row (D8): the {@code .jsc} signature, the Java target and the qualified class it is called
     * through, the conversion policy, the domain, whether a time value may be an argument, and the exact Java signature
     * the target must resolve to.
     */
    public record BindingRow(String module, String name, List<String> params, String result, String targetClass, String target,
                             List<String> prefix, String policy, String domain, boolean timeTotal, String javaSignature) {}

    /** Every {@code std} binding row, for the binding test. */
    public static List<BindingRow> bindings() {
        return Std.ROWS.values().stream().map(b -> new BindingRow(b.module(), b.name(), b.params().stream().map(Ty::show).toList(),
                Ty.show(b.result()), b.targetClass(), b.target(), b.prefix(), b.policy().name(), b.domain(), b.timeTotal(),
                b.javaSignature())).toList();
    }

    /**
     * The fixed messages the target of the binding row with Java signature {@code javaSignature} can trace (its adapter's
     * domain checks; D12), or none.
     */
    public static List<String> bindingMessages(String javaSignature) {
        for (Std.Binding b : Std.ROWS.values()) {
            if (javaSignature.equals(b.javaSignature())) return Std.MESSAGES.getOrDefault(b.name(), List.of());
        }
        return List.of();
    }

    /** The exact Java signature behind every {@code std} binding row (ADR-0018 D8). */
    public static List<String> bindingSignatures() {
        return Std.ROWS.values().stream().map(Std.Binding::javaSignature).filter(java.util.Objects::nonNull).toList();
    }

    /** {@code a/b/Name.java} for the contract a source declares. */
    public static String outputPath(String source) {
        return outputPath(source, null);
    }

    /**
     * {@code a/b/Name.java} for the contract a source declares, or for the library it is: a library's class is its
     * capitalised file name ({@code fees.jsc}: {@code Fees}).
     */
    public static String outputPath(String source, String fileName) {
        var h = Parser.header(source);
        String name = h.library() ? Checker.libraryClass(fileName == null ? "" : fileName, null, new Syntax.Pos(1, 1)) : h.contract();
        return h.pkg().replace('.', '/') + "/" + name + ".java";
    }
}
