package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code .jsc} 2 front end (ADR-0018): files that start with {@code jsc 2}. Parse ({@link Parser}), check into the
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
     * {@code Name.ir.json} beside the Java, for the JavaScript reference interpreter; {@code docs/reference/jsc-2-ir.md}).
     */
    public record Lowered(String java, List<Warning> diagnostics, Set<String> recursiveFns, String fileName, String javaPath,
                          String sourceMapJson, String abiJson, String irJson) {
        /** For each generated Java line (1-based) with a source: {@code [javaLine, jscLine, jscCol]}. */
        public List<int[]> sourceMap() {
            return Abi.sourceMap(java, fileName);
        }

        /** The warnings, each formatted {@code file:line:col: warning: message}. */
        public List<String> warnings() {
            return diagnostics.stream().map(w -> w.format(fileName)).toList();
        }
    }

    /** Whether a source is {@code .jsc} 2: its first word, after whitespace and comments, is {@code jsc}. */
    public static boolean isV2(String source) {
        return Parser.isV2(source);
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

    /** Checks and lowers a {@code .jsc} 2 source to a Java {@code @StateContract}. */
    public static Lowered lower(String source, String fileName) {
        checkFileName(fileName);
        Ir.Program p = Checker.check(Parser.parse(source), fileName);
        var warnings = p.warnings().stream().map(w -> new Warning(w.line(), w.col(), w.code(), w.message())).toList();
        String java = JavaEmitter.emit(p);
        String javaPath = p.pkg().replace('.', '/') + "/" + p.name() + ".java";
        return new Lowered(java, warnings, p.recursiveFns(), fileName, javaPath, Abi.sourceMapJson(java, fileName, javaPath), Abi.of(p),
                IrJson.write(p));
    }

    /** The reference interpreter (D15) of a {@code .jsc} 2 source, over the same checked IR the lowering reads. */
    public static Interpreter interpreter(String source, String fileName, Interpreter.Hashes hashes) {
        checkFileName(fileName);
        return new Interpreter(Checker.check(Parser.parse(source), fileName), hashes);
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

    /** The exact Java signature behind every {@code std} binding row (ADR-0018 D8). */
    public static List<String> bindingSignatures() {
        return Std.ROWS.values().stream().map(Std.Binding::javaSignature).filter(java.util.Objects::nonNull).toList();
    }

    /** {@code a/b/Name.java} for the contract a source declares. */
    public static String outputPath(String source) {
        var f = Parser.parse(source);
        return f.pkg().replace('.', '/') + "/" + f.contract().name() + ".java";
    }
}
