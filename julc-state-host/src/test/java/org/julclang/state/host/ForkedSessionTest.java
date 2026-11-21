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
        entries.removeFirst();
        // #151 review SB2: every recorded M1b vector (publications, owned and quorum calls, refusals) under the trace too.
        int vectors = 0;
        try (var files = Files.list(HostVectorsTest.DIRECTORY)) {
            for (var f : files.filter(v -> v.toString().endsWith(".json")).sorted().toList()) {
                success(hello(root, entries, fingerprint, JSON.readTree(f.toFile())));
                vectors++;
            }
        }
        assertTrue(vectors >= 20, "the recorded host vectors");
    }
    JsonNode hello(Path root, List<Path> entries, String fingerprint) throws Exception {
        return hello(root, entries, fingerprint, null);
    }
    JsonNode hello(Path root, List<Path> entries, String fingerprint, JsonNode vector) throws Exception {
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
            if (vector != null) for (String key : List.of("network", "clock", "time", "limits")) p.set(key, vector.required(key));
            assertTrue(writer.write(JSON.writeValueAsBytes(obj().put("jsonrpc","2.0").put("method","initialize").put("id",1).set("params",p))));
            var reader = new Frames.Reader(child.getInputStream());
            byte[] reply = reader.next();
            assertNotNull(reply, () -> diagnostics.toString());
            if (JSON.readTree(reply).has("result")) {
                if (vector == null) serve(writer, reader);
                else replay(writer, reader, vector);
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

    /**
     * #150 review SA5: everything the in-process replay covers, under the OS-level observation: all 23 plain recordings
     * (each prepared, checked byte for byte and discarded), a contract refusal, a failed read and a cancellation.
     */
    private static void serve(Frames.Writer writer, Frames.Reader reader) throws Exception {
        int id = 1;
        int prepared = 0;
        try (var paths = Files.list(ReplayTest.FIXTURES.resolve("dapp"))) {
            for (Path path : paths.sorted().toList()) {
                if (!Set.of("init", "action", "destroy").contains(JSON.readTree(path.toFile()).at("/call/kind").asText())) continue;
                var r = ReplayTest.Recording.read(path);
                var result = success(call(writer, reader, ++id, "prepare", r.params(), r::answer, null));
                assertEquals(r.fixture().required("unsignedTx"), result.at("/prepared/cbor"), path.toString());
                var tx = result.required("prepared");
                success(call(writer, reader, ++id, "settled", obj().put("status", "discarded")
                        .put("reservation", str(tx, "reservation")).put("txHash", str(tx, "txHash"))
                        .put("tip", r.point().required("slot").asLong()), r::answer, null));
                prepared++;
            }
        }
        assertEquals(23, prepared, "ADR-0022 §9 plain recordings");
        var r = IntegrationTest.record();
        // An earlier recording: reset the session's floor for now (reservations are kept).
        success(call(writer, reader, ++id, "reset", obj().put("now", r.point().required("slot").asLong()), r::answer, null));
        // A contract refusal, before and after reads.
        var refused = r.params().deepCopy();
        refused.withObject("/call").putObject("args").put("by", "-1");
        error("refusal", "require", call(writer, reader, ++id, "prepare", refused, r::answer, null));
        // A failed read.
        error("internal", "readFailed", call(writer, reader, ++id, "prepare", r.params(), q -> {
            var a = r.answer(q);
            a.remove("value");
            a.remove("absent");
            a.putObject("failed").put("message", "provider down");
            return a;
        }, null));
        // A cancellation while a read is outstanding, then the recorded call again.
        int cancelled = ++id;
        var reply = call(writer, reader, cancelled, "prepare", r.params(), r::answer, cancelled);
        assertEquals(-32800, reply.at("/error/code").asInt(), reply::toString);
        // A cancelled execution may still be unwinding: the protocol answers bad/busy until it has; retry as a client does.
        JsonNode next = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            next = call(writer, reader, ++id, "prepare", r.params(), r::answer, null);
            if (!"busy".equals(next.at("/error/data/code").asText())) break;
            Thread.sleep(100);
        }
        var again = success(next);
        assertEquals(r.fixture().required("unsignedTx"), again.at("/prepared/cbor"));
    }

    /** A recorded host vector (HostVectorsTest): its assertions, its call with its recorded answers, its outcome. */
    private static void replay(Frames.Writer writer, Frames.Reader reader, JsonNode v) throws Exception {
        int id = 1;
        for (var assertion : v.required("initialSession").required("registered"))
            success(call(writer, reader, ++id, "registered", assertion, q -> { throw new AssertionError(q.toString()); }, null));
        var reads = v.required("reads").elements();
        var response = call(writer, reader, ++id, str(v, "method"), v.required("params"), q -> {
            assertTrue(reads.hasNext(), q::toString);
            var pair = reads.next();
            assertEquals(pair.required("request"), q);
            return pair.required("answer");
        }, null);
        HostVectorsTest.checkOutcome(v, response);
    }

    /** One forward call, answering its reverse reads; with {@code cancel}, cancels it at its first read instead. */
    private static JsonNode call(Frames.Writer writer, Frames.Reader reader, int id, String method, JsonNode params,
                                 java.util.function.Function<JsonNode, JsonNode> answer, Integer cancel) throws Exception {
        assertTrue(writer.write(JSON.writeValueAsBytes(obj().put("jsonrpc","2.0").put("method",method).put("id",id).set("params",params))));
        var frame = JSON.readTree(reader.next());
        boolean sent = false;
        while (frame.has("method")) {
            assertEquals("chain/read", frame.required("method").asText());
            if (cancel != null && !sent) {
                assertTrue(writer.write(JSON.writeValueAsBytes(obj().put("jsonrpc","2.0").put("method","$/cancelRequest")
                        .set("params", obj().put("id", cancel)))));
                sent = true;
            } else if (cancel == null) {
                var a = obj().put("jsonrpc","2.0");
                a.set("id",frame.required("id"));
                a.set("result",answer.apply(frame.required("params")));
                assertTrue(writer.write(JSON.writeValueAsBytes(a)));
            }
            frame = JSON.readTree(reader.next());
        }
        assertEquals(id, frame.required("id").asInt(), frame::toString);
        return frame;
    }

    // The first stdin read marks the serving interval, after the JVM's own launch and perf-data setup.
    private static void checkTrace(Path log) throws IOException {
        boolean serving = false;
        try (var lines = Files.lines(log)) {
            for (var iterator = lines.iterator(); iterator.hasNext();) {
                String line = iterator.next();
                if (line.contains(" read(0,")) serving = true;
                if (!serving) continue;
                // #150 review SA6: no socket of any kind, no process, no file created, removed, renamed or opened
                // for writing. Threads (clone with CLONE_THREAD) are the JVM's own and allowed.
                boolean opened = (line.contains(" openat(") || line.contains(" openat2(") || line.contains(" open(")) && (line.contains("O_WRONLY")
                        || line.contains("O_RDWR") || line.contains("O_CREAT") || line.contains("O_TRUNC"));
                boolean process = line.matches(".* (fork|vfork|execve|execveat)\\(.*")
                        || line.matches(".* clone3?\\(.*") && !line.contains("CLONE_THREAD");
                boolean socket = line.matches(".* (connect|socket|socketpair|bind|listen|accept4?|sendto|sendmsg)\\(.*");
                // #150 final review F-C1: also rmdir, ftruncate, the chmod family, utimensat and mknod.
                boolean files = line.matches(".* (creat|mkdirat?|rmdir|unlinkat?|renameat2?|rename|f?truncate|linkat?|symlinkat?|f?chmod(at)?|utimensat|mknod(at)?)\\(.*");
                // The JVM's own perf-data file (/tmp/hsperfdata_<user>/<pid>, created before serving and removed at
                // exit) is the one recorded exception (dev-host-1 I10; #150 review SA7).
                // Only its removal is exempt: any other operation on the perf-data path still fails (#150 review SA8).
                if (line.matches(".* unlink(at)?\\(.*\"/tmp/hsperfdata_[^/\"]+/[0-9]+\".*") && !socket && !process && !opened) continue;
                assertFalse(opened || process || socket || files, line);
            }
        }
        assertTrue(serving, "strace did not observe the host reading stdin");
    }
}
