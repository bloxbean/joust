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
        var home = Files.createTempDirectory(temp, "home");
        var tmp = Files.createTempDirectory(temp, "tmp");
        var trace = Files.createTempFile(temp, "syscalls", ".log");
        var argv = new ArrayList<String>();
        String audit = System.getProperty("joust.host.traceLauncher");
        if (audit != null) argv.addAll(List.of(audit, trace.toString()));
        argv.addAll(List.of(System.getProperty("joust.host.java"), "-XX:+DisplayVMOutputToStderr",
                "-Xlog:disable", "-Xlog:all=warning:stderr", "--enable-native-access=ALL-UNNAMED",
                "-Duser.home=" + home, "-Djava.io.tmpdir=" + tmp, "-Djoust.dumpTx=" + dir,
                "-cp", String.join(File.pathSeparator, entries.stream().map(Path::toString).toList()),
                DevHost.class.getName(), "--stdio"));
        var cmd = new ProcessBuilder(argv).directory(dir.toFile());
        cmd.environment().keySet().removeIf(k -> !Set.of("PATH", "LANG", "SYSTEMROOT").contains(k.toUpperCase(Locale.ROOT))
                && !k.startsWith("LC_"));
        cmd.environment().put("HOME", home.toString());
        if (File.separatorChar == '\\') {
            cmd.environment().put("USERPROFILE", home.toString());
            cmd.environment().put("TEMP", tmp.toString());
            cmd.environment().put("TMP", tmp.toString());
        }
        var diagnostics = temp.resolve("diagnostics");
        var child = cmd.redirectError(diagnostics.toFile()).start();
        try {
            var writer = new Frames.Writer(child.getOutputStream());
            var p = init();
            p.withObject("/build").put("snapshot",root.toString()).put("digest",fingerprint);
            assertTrue(writer.write(JSON.writeValueAsBytes(obj().put("jsonrpc","2.0").put("method","initialize").put("id",1).set("params",p))));
            var reader = new Frames.Reader(child.getInputStream());
            byte[] reply = reader.next();
            assertNotNull(reply, () -> diagnostics.toString());
            if (JSON.readTree(reply).has("result")) {
                var fixture = IntegrationTest.record();
                assertTrue(writer.write(JSON.writeValueAsBytes(obj().put("jsonrpc","2.0").put("method","prepare")
                        .put("id",2).set("params",fixture.params()))));
                var frame = JSON.readTree(reader.next());
                while (frame.has("method")) {
                    assertEquals("chain/read", frame.required("method").asText());
                    var answer = obj().put("jsonrpc","2.0");
                    answer.set("id",frame.required("id"));
                    answer.set("result",fixture.answer(frame.required("params")));
                    assertTrue(writer.write(JSON.writeValueAsBytes(answer)));
                    frame = JSON.readTree(reader.next());
                }
                assertEquals(2, frame.required("id").asInt());
                assertEquals(fixture.fixture().required("unsignedTx"), success(frame).at("/prepared/cbor"));
            }
            assertTrue(writer.write(JSON.writeValueAsBytes(obj().put("jsonrpc","2.0").put("method","exit"))));
            assertTrue(child.waitFor(5,TimeUnit.SECONDS));
            assertEquals(0,child.exitValue(),Files.readString(diagnostics));
            for (Path empty : List.of(dir, home, tmp)) {
                try (var files = Files.list(empty)) { assertEquals(List.of(), files.toList(), empty.toString()); }
            }
            if (audit != null) checkTrace(trace);
            return JSON.readTree(reply);
        } finally { child.destroyForcibly(); }
    }

    // The first stdin read marks the serving interval, after the JVM's own launch and perf-data setup.
    private static void checkTrace(Path log) throws IOException {
        boolean serving = false;
        try (var lines = Files.lines(log)) {
            for (var iterator = lines.iterator(); iterator.hasNext();) {
                String line = iterator.next();
                if (line.contains(" read(0,")) serving = true;
                if (!serving) continue;
                assertFalse(line.contains(" connect(") || line.contains(" execve(") || line.contains(" socket(")
                        || line.contains(" openat(") && (line.contains("O_WRONLY") || line.contains("O_RDWR")
                        || line.contains("O_CREAT") || line.contains("O_TRUNC")), line);
            }
        }
        assertTrue(serving, "strace did not observe the host reading stdin");
    }
}
