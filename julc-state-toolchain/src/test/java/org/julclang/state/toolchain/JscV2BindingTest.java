package org.julclang.state.toolchain;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePathScanner;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.state.dsl.v2.JscV2.BindingRow;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D8 (J7, review F11): every {@code std} binding row has a {@code .jsc} signature, a Java target, a conversion
 * policy and a domain. JuLC's scoped javac attribution of the on-chain sources on this class path (the pinned
 * julc-stdlib and JOUST's API, as the npm toolchain sees them) compiles a call of each row's target with arguments of
 * the row's Java types, and the method javac resolves (overloads included) must be exactly the row's signature; the
 * policy decides the Java types (no {@code Int} reaches a {@code long} or {@code int} parameter except through an
 * adapter). A wrong row fails, which the negative cases show. The stdlib methods the adapters call are checked too.
 */
class JscV2BindingTest {

    /** What BytesAdapter calls: narrowing happens only there, after its domain check. */
    static final List<String> ADAPTER_CALLEES = List.of(
            "org.julclang.stdlib.lib.ByteStringLib.slice(byte[],long,long):byte[]",
            "org.julclang.stdlib.lib.ByteStringLib.at(byte[],long):long",
            "org.julclang.stdlib.lib.ByteStringLib.length(byte[]):long");

    static String java(String jscType) {
        return switch (jscType) {
            case "Int" -> "java.math.BigInteger";
            case "Bool" -> "boolean";
            case "Bytes", "Key" -> "byte[]";
            default -> throw new IllegalArgumentException(jscType);
        };
    }

    static List<JavacFrontend.Unit> pool() {
        var pool = new ArrayList<JavacFrontend.Unit>();
        LibrarySourceResolver.scanClasspathSources(Toolchain.class.getClassLoader(), p -> {})
                .values().forEach(l -> pool.add(JavacFrontend.Unit.jar(l.source())));
        return pool;
    }

    /** The problems with a set of rows: empty when every row is right. */
    static List<String> check(List<BindingRow> rows) {
        var problems = new ArrayList<String>();
        var probe = new StringBuilder("package probe;\n");
        for (BindingRow r : rows) probe.append("import ").append(r.targetClass()).append(";\n");
        probe.append("class Probe {\n");
        for (int i = 0; i < rows.size(); i++) {
            BindingRow r = rows.get(i);
            // The policy fixes the Java types around the call (D8).
            String ret = switch (r.policy()) {
                case "LONG_RESULT" -> "long";
                case "DIRECT", "ADAPTER" -> java(r.result());
                default -> throw new IllegalArgumentException(r.policy());
            };
            if (!r.policy().equals("ADAPTER") && (r.params().contains("Int") || r.result().equals("Int") && !r.policy().equals("LONG_RESULT"))) {
                problems.add(r.name() + ": an Int crosses into Java without an adapter (policy " + r.policy() + ")");
            }
            if (r.domain() == null || r.domain().isBlank()) problems.add(r.name() + ": no domain");
            var ps = new ArrayList<String>();
            var as = new ArrayList<String>();
            for (int k = 0; k < r.params().size(); k++) {
                ps.add(java(r.params().get(k)) + " a" + k);
                as.add("a" + k);
            }
            probe.append("    static ").append(ret).append(" r").append(i).append("(").append(String.join(", ", ps)).append(") { return ")
                    .append(r.target()).append("(").append(String.join(", ", as)).append("); }\n");
        }
        probe.append("}\n");
        Map<Integer, String> resolved = new LinkedHashMap<>();
        var errors = new ArrayList<String>();
        JavacFrontend.shared().attribute(List.of(JavacFrontend.Unit.of(probe.toString())), pool(), a -> {
            for (var d : a.result().diagnostics()) if (!d.message().startsWith("warning")) errors.add(d.message() + " at line " + d.line());
            if (a.trees() == null) return null;
            for (var unit : a.roots()) {
                new TreePathScanner<Void, Void>() {
                    String method;

                    @Override
                    public Void visitMethod(MethodTree m, Void v) {
                        method = m.getName().toString();
                        return super.visitMethod(m, v);
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree t, Void v) {
                        if (a.trees().getElement(getCurrentPath()) instanceof ExecutableElement e && method.startsWith("r")) {
                            String owner = ((TypeElement) e.getEnclosingElement()).getQualifiedName().toString();
                            resolved.put(Integer.parseInt(method.substring(1)), owner + "." + e.getSimpleName() + "(" + e.getParameters().stream()
                                    .map(p -> p.asType().toString()).collect(Collectors.joining(",")) + "):" + e.getReturnType());
                        }
                        return super.visitMethodInvocation(t, v);
                    }
                }.scan(unit, null);
            }
            return null;
        });
        if (!errors.isEmpty()) problems.add("the probe does not compile: " + errors);
        for (int i = 0; i < rows.size(); i++) {
            String got = resolved.get(i);
            if (!rows.get(i).javaSignature().equals(got)) {
                problems.add(rows.get(i).name() + ": its target resolves to " + got + ", the row says " + rows.get(i).javaSignature());
            }
        }
        return problems;
    }

    @Test
    void everyRowsTargetResolvesToItsSignature_underItsPolicy() {
        var rows = JscV2.bindings();
        assertEquals(List.of(), check(rows), "std binding rows that disagree with the attributed sources");
        for (BindingRow r : rows) {
            System.out.println("[D8] " + r.module() + " " + r.name() + "(" + String.join(", ", r.params()) + "): " + r.result() + " -> "
                    + r.javaSignature() + "  [" + r.policy() + "; " + r.domain() + "; time " + (r.timeTotal() ? "total" : "refused") + "]");
        }
    }

    /** F11: a row whose target and signature disagree, or whose policy lets an Int narrow, fails. */
    @Test
    void aWrongRowFails() {
        var length = JscV2.bindings().stream().filter(r -> r.name().equals("length")).findFirst().orElseThrow();
        var wrongSignature = new BindingRow(length.module(), "length", length.params(), length.result(), length.targetClass(), length.target(),
                length.policy(), length.domain(), true, "org.julclang.stdlib.lib.ByteStringLib.length(byte[]):int");
        assertFalse(check(List.of(wrongSignature)).isEmpty(), "a mistyped signature");
        var wrongTarget = new BindingRow(length.module(), "length", length.params(), length.result(), length.targetClass(),
                "ByteStringLib.at", length.policy(), length.domain(), true, length.javaSignature());
        assertFalse(check(List.of(wrongTarget)).isEmpty(), "a template calling another method");
        var narrowing = new BindingRow("std/bytes", "at", List.of("Bytes", "Int"), "Int", "org.julclang.stdlib.lib.ByteStringLib",
                "ByteStringLib.at", "LONG_RESULT", "index", false, "org.julclang.stdlib.lib.ByteStringLib.at(byte[],long):long");
        var problems = check(List.of(narrowing));
        assertTrue(problems.stream().anyMatch(p -> p.contains("without an adapter")), problems.toString());
    }

    @Test
    void theAdaptersCalleesAreDeclared() {
        List<String> missing = JavacFrontend.shared().attribute(List.of(JavacFrontend.Unit.of("package probe; class Probe {}")), pool(), a -> {
            var out = new ArrayList<String>();
            for (String sig : ADAPTER_CALLEES) {
                String owner = sig.substring(0, sig.lastIndexOf('.', sig.indexOf('(')));
                String method = sig.substring(owner.length() + 1, sig.indexOf('('));
                TypeElement type = a.elements().getTypeElement(owner);
                var found = new TreeSet<String>();
                if (type != null) {
                    for (var e : type.getEnclosedElements()) {
                        if (e instanceof ExecutableElement m && m.getSimpleName().contentEquals(method) && m.getModifiers().contains(Modifier.STATIC)) {
                            found.add(owner + "." + method + "(" + m.getParameters().stream().map(p -> p.asType().toString())
                                    .collect(Collectors.joining(",")) + "):" + m.getReturnType());
                        }
                    }
                }
                if (!found.contains(sig)) out.add(sig + "  (declared: " + found + ")");
            }
            return out;
        });
        assertEquals(List.of(), missing);
    }
}
