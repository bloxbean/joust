package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

/** D2-D4: initialize the real entry point against its JVM class path, including jar dependencies. */
class ForkedSessionTest {
    @TempDir Path temp;
    @Test void verifiedSnapshotAndIncompatibleRuntimeOverStdio() throws Exception {
        var root = Files.createDirectory(temp.resolve("snapshot"));
        List<Path> entries = new ArrayList<>();
        var cp = System.getProperty("joust.host.testClassPath",System.getProperty("java.class.path"));
        for (String path : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path original = Path.of(path);
            if (!Files.exists(original)) continue; // NO-SOURCE resource directories
            Path dest = root.resolve(entries.size() + (Files.isDirectory(original) ? "-classes" : ".jar"));
            entries.add(dest);
            if (Files.isDirectory(original)) {
                try (var tree = Files.walk(original)) {
                    for (var f : tree.toList()) {
                        Path copy = dest.resolve(original.relativize(f));
                        if (Files.isDirectory(f)) Files.createDirectories(copy);
                        else Files.copy(f,copy);
                    }
                }
            } else {
                try { Files.createLink(dest,original); } // immutable dependency in this test
                catch (UnsupportedOperationException | IOException e) { Files.copy(original,dest); }
            }
        }
        String fingerprint = Launch.scan(root,entries).fingerprint();
        var ok = success(hello(root,entries,fingerprint));
        assertEquals(fingerprint,ok.get("fingerprint").asText());
        var catalog = ok.get("contracts");
        assertTrue(catalog.findValues("name").stream().anyMatch(n -> n.asText().endsWith(".Counter")));
        assertTrue(catalog.findValues("name").stream().anyMatch(n -> n.asText().endsWith(".Bank")));
        var treasury = catalog.findParents("unsupported").stream().filter(n -> n.path("name").asText().endsWith(".Treasury"))
                .findFirst().orElseThrow();
        assertEquals("params",treasury.get("unsupported").asText());
        assertTrue(treasury.at("/scriptHashes/unapplied").asText().matches("[0-9a-f]{56}"));

        // Match the digest of a *different* selected runtime so only the independent version test can catch it.
        var old = Files.createDirectory(root.resolve("old"));
        var java = temp.resolve("RuntimeVersion.java");
        Files.writeString(java,"""
            package org.julclang.state.runtime;
            class StateRuntime {}
            public class RuntimeVersion { public static final String VERSION = "incompatible"; }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",old.toString(),java.toString()));
        entries.addFirst(old);
        error("bad","stageMismatch",hello(root,entries,fingerprint));
        error("unsupported","runtimeVersion",hello(root,entries,Launch.scan(root,entries).fingerprint()));
    }
    JsonNode hello(Path root, List<Path> entries, String fingerprint) throws Exception {
        var dir = Files.createTempDirectory(temp,"working");
        var cmd = new ProcessBuilder(System.getProperty("joust.host.java"),"-XX:+DisplayVMOutputToStderr",
                "-Xlog:disable","-Xlog:all=warning:stderr","--enable-native-access=ALL-UNNAMED",
                "-cp",String.join(File.pathSeparator,entries.stream().map(Path::toString).toList()),
                DevHost.class.getName(),"--stdio").directory(dir.toFile());
        var diagnostics = temp.resolve("diagnostics");
        var child = cmd.redirectError(diagnostics.toFile()).start();
        try {
            var writer = new Frames.Writer(child.getOutputStream());
            var p = init();
            p.withObject("/build").put("snapshot",root.toString()).put("digest",fingerprint);
            assertTrue(writer.write(JSON.writeValueAsBytes(obj().put("jsonrpc","2.0").put("method","initialize").put("id",1).set("params",p))));
            byte[] reply = new Frames.Reader(child.getInputStream()).next();
            assertNotNull(reply,() -> diagnostics.toString());
            assertTrue(writer.write(JSON.writeValueAsBytes(obj().put("jsonrpc","2.0").put("method","exit"))));
            assertTrue(child.waitFor(5,TimeUnit.SECONDS));
            assertEquals(0,child.exitValue(),Files.readString(diagnostics));
            try (var files = Files.list(dir)) { assertEquals(0,files.count()); }
            return JSON.readTree(reply);
        } finally { child.destroyForcibly(); }
    }
}
