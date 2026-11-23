package org.julclang.state.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.gradle.HostSpecConsumerTest.*;

/** Independent scripted D2/D3 client. All class-path bytes, including directory listings, are verified on the copy. */
final class SnapshotClient {
    interface BeforeDescribe { void run() throws Exception; }

    static void describe(Path project, Path privateDir, JsonNode spec, BeforeDescribe beforeDescribe) throws Exception {
        assertEquals(1, spec.required("protocol").intValue());
        assertEquals("org.julclang.state.host.DevHost", spec.required("mainClass").textValue());
        Path java = Path.of(spec.required("java").textValue());
        assertTrue(java.isAbsolute() && Files.isExecutable(java));
        assertTrue(Set.of("java", "java.exe").contains(java.getFileName().toString()));
        assertEquals("bin", java.getParent().getFileName().toString());
        var release = new Properties();
        try (var in = Files.newInputStream(java.getParent().getParent().resolve("release"))) { release.load(in); }
        assertTrue(Runtime.Version.parse(release.getProperty("JAVA_VERSION").replace("\"", "")).feature() >= 25);

        Path staging = Files.createTempDirectory(privateDir, "copy");
        var entries = new ArrayList<String>();
        var digest = sha256();
        for (var entry : spec.required("launch")) {
            int ordinal = entries.size();
            assertEquals(ordinal, entry.required("ordinal").intValue());
            String kind = entry.required("kind").textValue();
            Path from = Path.of(entry.required("path").textValue());
            assertTrue(from.isAbsolute());
            String destName = ordinal + (kind.equals("jar") ? ".jar" : "-classes");
            entries.add(destName);
            Path dest = staging.resolve(destName);
            if (kind.equals("jar")) {
                copyJar(from, dest);
                try (var jar = new JarFile(dest.toFile(), false)) {
                    var mf = jar.getManifest();
                    assertTrue(mf == null || mf.getMainAttributes().getOrDefault(Attributes.Name.CLASS_PATH, "").equals(""));
                }
                verifyFile(dest, entry.required("sha256"), digest, ordinal, kind, "");
            } else {
                assertEquals("dir", kind);
                Files.createDirectory(dest);
                var files = entry.required("files");
                var names = new ArrayList<String>();
                files.fieldNames().forEachRemaining(names::add);
                names.sort((a,b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
                // The tree is exactly the manifest's regular files (by name, not count) and directories: a FIFO or
                // device would block the copy before any digest or timeout applies (#150 Codex re-review).
                var actual = new ArrayList<String>();
                // The directory itself as the plugin scanned it (LaunchManifest resolves a symlinked launch directory).
                try (var tree = Files.walk(from.toRealPath())) {
                    for (var p : (Iterable<Path>) tree::iterator) {
                        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue;
                        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a regular file: " + p);
                        actual.add(from.toRealPath().relativize(p).toString().replace(File.separatorChar, '/'));
                    }
                }
                actual.sort((a,b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
                assertEquals(names, actual);
                for (String name : names) {
                    Path copy = dest.resolve(name).normalize();
                    assertTrue(copy.startsWith(dest));
                    Files.createDirectories(copy.getParent());
                    copyJar(from.resolve(name), copy);   // a regular file again, right before it is opened
                    verifyFile(copy, files.required(name), digest, ordinal, kind, name);
                }
            }
        }
        String hex = HexFormat.of().formatHex(digest.digest());
        assertEquals(spec.required("digest").textValue(), hex);
        Path snapshot = Files.move(staging, privateDir.resolve(hex), StandardCopyOption.ATOMIC_MOVE);
        String cp = String.join(File.pathSeparator, entries.stream().map(snapshot::resolve).map(Path::toString).toList());
        try (var tree = Files.walk(snapshot)) {
            tree.sorted(Comparator.reverseOrder()).forEach(p -> assertTrue(p.toFile().setReadOnly()));
        }
        var pb = new ProcessBuilder(java.toString(), "-XX:+DisplayVMOutputToStderr", "-Xlog:disable", "-Xlog:all=warning:stderr",
                "--enable-native-access=ALL-UNNAMED", "-cp", cp, spec.required("mainClass").asText(), "--stdio");
        boolean windows = File.separatorChar == '\\';
        Set<String> allowed = windows ? Set.of("PATH", "HOME", "LANG", "USERPROFILE", "SYSTEMROOT", "TEMP", "TMP")
                : Set.of("PATH", "HOME", "LANG");
        pb.environment().keySet().removeIf(key -> !allowed.contains(windows ? key.toUpperCase(Locale.ROOT) : key)
                && !key.startsWith("LC_"));
        Process child = null;
        try {
            child = pb.directory(project.toFile()).start();
            var diagnostics = drain(child.getErrorStream());
            var p = (ObjectNode) JSON.readTree("""
                    {"protocol":[1],"network":{"networkId":0,"protocolMagic":42,"mainnet":false},
                     "clock":{"zeroSlot":0,"zeroTimeMs":1700000000000,"slotLengthMs":1000},
                     "time":{"maxTtlSlots":900,"settlementSlots":60,"maxNowAheadSlots":900},
                     "limits":{"callDeadlineMs":60000,"readsPerCall":256,"readBytesPerCall":33554432},"restoring":false}
                    """);
            p.putObject("build").put("snapshot", snapshot.toString()).put("digest", hex);
            var in = new DataInputStream(new BufferedInputStream(child.getInputStream()));
            var hello = rpc(child, in, 1, "initialize", p);
            assertEquals(hex, hello.required("fingerprint").textValue());
            var contract = hello.required("contracts").get(0);
            assertEquals(CONTRACT, contract.required("name").textValue());
            beforeDescribe.run();
            var result = rpc(child, in, 2, "describe", JSON.createObjectNode().put("contract", CONTRACT));
            assertEquals(CONTRACT, result.at("/description/preamble/title").asText());
            assertEquals(0, result.required("messages").required("revision").intValue());

            // D13: the client can still independently read the original validator and description from the sibling jar.
            String validator = "META-INF/plutus/CounterValidator.plutus.json";
            for (var entry : spec.required("launch")) {
                if (!Path.of(entry.required("path").asText()).getFileName().toString().startsWith("sibling")) continue;
                try (var jar = new JarFile(snapshot.resolve(entries.get(entry.get("ordinal").asInt())).toFile())) {
                    assertNotNull(jar.getEntry(validator));
                    try (var anchor = jar.getInputStream(jar.getJarEntry("META-INF/joust/" + CONTRACT + ".json"))) {
                        assertEquals(JSON.readTree(anchor), result.required("description"));
                    }
                }
            }
            send(child, JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "exit"));
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), diagnostics.get(5, TimeUnit.SECONDS));
        } finally {
            if (child != null) child.destroyForcibly();
            // Restore permissions solely for the test's temporary directory cleanup.
            try (var tree = Files.walk(snapshot)) { tree.forEach(p -> p.toFile().setWritable(true)); }
        }
    }

    private static CompletableFuture<String> drain(InputStream stderr) {
        return CompletableFuture.supplyAsync(() -> {
            var kept = new ByteArrayOutputStream();
            try (stderr) {
                byte[] buffer = new byte[4096];
                for (int n; (n = stderr.read(buffer)) != -1;) {
                    kept.write(buffer, 0, Math.min(n, 65536 - kept.size()));
                }
                return kept.toString(StandardCharsets.UTF_8);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    private static MessageDigest sha256() throws NoSuchAlgorithmException { return MessageDigest.getInstance("SHA-256"); }

    private static void verifyFile(Path file, JsonNode expected, MessageDigest digest, int ordinal,
                                   String kind, String name) throws Exception {
        var sha = sha256();
        try (var in = new DigestInputStream(Files.newInputStream(file), sha)) { in.transferTo(OutputStream.nullOutputStream()); }
        String hash = HexFormat.of().formatHex(sha.digest());
        assertEquals(expected.textValue(), hash, file.toString());
        digest.update(("%s %d %s %s\n".formatted(hash, ordinal, kind, name)).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(Process child, JsonNode value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        var out = child.getOutputStream();
        out.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static JsonNode rpc(Process child, DataInputStream in, int id, String method, ObjectNode params) throws Exception {
        send(child, JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method).set("params", params));
        return CompletableFuture.supplyAsync(() -> {
            try {
                var header = new StringBuilder();
                while (!header.toString().endsWith("\r\n\r\n")) {
                    assertTrue(header.length() < 1024);
                    header.append((char) in.readUnsignedByte());
                }
                assertTrue(header.toString().matches("Content-Length: [0-9]+\r\n\r\n"), header::toString);
                int size = Integer.parseInt(header.substring("Content-Length: ".length()).trim());
                assertTrue(size > 0 && size <= 4194304);
                byte[] bytes = new byte[size];
                in.readFully(bytes);
                var answer = JSON.readTree(bytes);
                assertEquals("2.0", answer.required("jsonrpc").textValue());
                assertEquals(id, answer.required("id").intValue());
                assertFalse(answer.has("error"), answer::toString);
                return answer.required("result");
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }).get(60, TimeUnit.SECONDS);
    }

    /**
     * Copies a launch jar after checking it is a regular file: a stale or malformed spec naming a FIFO (or a device)
     * would otherwise block the copy before any digest or timeout applies (#150 Codex review).
     */
    static void copyJar(Path from, Path dest) throws IOException {
        if (!Files.isRegularFile(from)) throw new IOException("launch file is not a regular file: " + from);
        Files.copy(from, dest);
    }
}
