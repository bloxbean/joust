package org.julclang.state.processor;

import org.julclang.state.dsl.v2.JavaLibraries;
import org.julclang.state.dsl.v2.JscModules;
import org.julclang.state.dsl.v2.JscV2;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D9 (M4b), review r2 (design F1, F2; security S1): a contract lowered from {@code .jsc} with {@code java:}
 * libraries names them in its Javadoc; the processor reads that line (a real lowering, so the two sides cannot drift) and
 * the client checks the libraries' classes when it loads. A contract without them gets no check at all.
 */
class JavaLibrariesClientTest {

    static final String FEES = """
            package lib;
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary
            public class Fees {
                public static BigInteger fee() { return BigInteger.ONE; }
            }
            """;

    static String client(String jsc) throws Exception {
        var fees = new JavaLibraries.Library("lib.Fees", "0".repeat(64), List.of(new JavaLibraries.Fn("fee", List.of(), List.of(),
                JavaLibraries.JType.INT, null)), List.of(), List.of(), List.of("lib.Fees"));
        String java = JscV2.lower(jsc, "C.jsc", "app/C.jsc", JscModules.NONE, JavaLibraries.of(Map.of("lib.Fees", fees))).java();
        Path out = Files.createTempDirectory("joust-javalib-client");
        var units = List.of(source("app/C.java", java), source("lib/Fees.java", FEES));
        var diags = new DiagnosticCollector<JavaFileObject>();
        var task = ToolProvider.getSystemJavaCompiler().getTask(null, null, diags, List.of("-d", out.toString(), "-s", out.toString(),
                "-classpath", System.getProperty("java.class.path")), null, units);
        task.setProcessors(List.of(new JoustProcessor()));
        assertTrue(task.call(), diags.getDiagnostics().toString());
        return Files.readString(out.resolve("app/CClient.java"));
    }

    static JavaFileObject source(String path, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return text;
            }
        };
    }

    @Test
    void theClient_checksTheContractsJavaLibraries_andOnlyThen() throws Exception {
        String with = client("""
                jsc 1
                package app
                import { fee } from "java:lib.Fees"
                contract C {
                  state { n: Int }
                  init() { return { n: fee() } }
                }
                """);
        assertTrue(with.contains("org.julclang.state.runtime.JavaLibraryCheck.verify(CClient.class, CValidator.class, \"app.C\", "
                + "java.util.List.of(\"lib.Fees\"));"), with);
        assertTrue(with.indexOf("JavaLibraryCheck.verify") < with.indexOf("SPEC ="), "the check runs before anything of the client");
        String without = client("""
                jsc 1
                package app
                contract C {
                  state { n: Int }
                  init() { return { n: 1 } }
                }
                """);
        assertFalse(without.contains("JavaLibraryCheck"), without);
    }
}
