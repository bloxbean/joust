package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.JscV2;
import org.julclang.state.processor.JoustProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1c review C1: the toolchain builds validators, descriptions and transition programs, but not the Java
 * client the processor generates on Gradle, so a name clash inside the generated client passes {@code Toolchain.build}.
 * Here each port's generated Java, and a batched fixture, are compiled by javac with the JOUST processor, which
 * generates the client and compiles it in the same task.
 */
class JscV2GeneratedClientTest {

    /** javac with the JOUST processor over one source; the errors, empty when it compiles (client included). */
    static List<String> compile(String pkg, String name, String java) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        var src = new SimpleJavaFileObject(URI.create("string:///" + pkg.replace('.', '/') + "/" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return java;
            }
        };
        var out = Files.createTempDirectory("joust-client-");
        var task = compiler.getTask(null, null, diags, List.of("-d", out.toString(), "-s", out.toString(), "-implicit:class",
                "-classpath", System.getProperty("java.class.path")), null, List.of(src));
        task.setProcessors(List.of(new JoustProcessor()));
        task.call();
        return diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getSource() == null ? d.getMessage(null) : d.getSource().getName() + ":" + d.getLineNumber() + ": " + d.getMessage(null))
                .collect(Collectors.toList());
    }

    @Test
    void thePorts_andABatchedFixture_compileWithTheirClients() throws Exception {
        for (String n : List.of("Vault", "Escrow", "Crowdfund", "Token", "Pool")) {
            var l = JscV2.lower(Files.readString(JscV2M1aFourWayTest.DSL.resolve(n + ".jsc")), n + ".jsc");
            assertEquals(List.of(), compile("org.julclang.state.examples.dsl", n, l.java()), n);
        }
        // Batched actions whose parameters take every other name the client's intent method could plausibly use.
        String fixture = """
                jsc 2
                package probe
                contract Many {
                  state { admin: Key, n: Int }
                  init() { return { admin: caller, n: 0 } }
                  action a(instance2: Int, payLovelace2: Int, call: Int, intent2: Int, seed2: Int, a: Int) payable batched {
                    return { ...state, n: n + instance2 + payLovelace2 + call + intent2 + seed2 + a }
                  }
                  action b(owner: Key, refund: Int, received2: Int) batched only open {
                    pay(owner, refund + received2)
                    return state
                  }
                }
                """;
        assertEquals(List.of(), compile("probe", "Many", JscV2.lower(fixture, "Many.jsc").java()));
    }

    @Test
    void aBatchedDeadlineParameter_wouldBreakTheClient() throws Exception {
        // The repro: the Java a 'deadline' parameter of a batched action lowers to. Its generated client does not compile
        // (goIntent(Account, BigInteger deadline, Instant deadline)), which is why the checker refuses it (JSC0216).
        String java = """
                package probe;
                import java.math.BigInteger;
                import org.julclang.state.api.*;
                @StateContract
                public class Deadline {
                    public record State(BigInteger n) {}
                    @Init public static State init() { return new State(BigInteger.ZERO); }
                    @Action @Batched(only = true) public static State go(State s, BigInteger deadline, Ctx ctx) { return new State(deadline); }
                }
                """;
        var errors = compile("probe", "Deadline", java);
        assertTrue(errors.stream().anyMatch(e -> e.contains("variable deadline is already defined")), errors.toString());
        var e = assertThrows(org.julclang.state.dsl.JscException.class, () -> JscV2.lower("""
                jsc 2
                package probe
                contract Deadline {
                  state { n: Int }
                  init() { return { n: 0 } }
                  action go(deadline: Int) batched only { return { n: deadline } }
                }
                """, "Deadline.jsc"));
        assertEquals("6:13", e.line + ":" + e.col, e.getMessage());
        assertTrue(e.getMessage().contains("'deadline' is a name the generated client uses"), e.getMessage());
    }
}
