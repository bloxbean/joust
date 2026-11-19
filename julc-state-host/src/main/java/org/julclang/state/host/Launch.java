package org.julclang.state.host;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** The ordered, byte-addressed launch manifest (ADR-0022 D3/D4). No project classes are loaded by this scan. */
record Launch(String fingerprint, List<String> descriptions) {
    static List<Path> classPath() {
        return Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator), -1))
                .map(Path::of).toList();
    }

    static Launch scan(Path snapshot, List<Path> entries) {
        var digest = sha();
        var names = new TreeSet<String>();
        try {
            Path root = snapshot.toRealPath();
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i).toRealPath();
                if (!entry.startsWith(root)) throw new IOException("class path outside snapshot");
                if (Files.isDirectory(entry)) {
                    try (var stream = Files.walk(entry)) {
                        var files = stream.filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                                .map(entry::relativize).map(Path::toString).map(s -> s.replace(File.separatorChar, '/'))
                                .sorted((a,b) -> Arrays.compareUnsigned(utf8(a), utf8(b))).toList();
                        for (String name : files) {
                            Path file = entry.resolve(name);
                            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("non-regular entry " + name);
                            line(digest, i, "dir", name, file);
                            description(names, name);
                        }
                    }
                } else {
                    line(digest, i, "jar", "", entry);
                    try (var jar = new JarFile(entry.toFile(), false)) {
                        var manifest = jar.getManifest();
                        if (manifest != null && !manifest.getMainAttributes().getOrDefault(
                                java.util.jar.Attributes.Name.CLASS_PATH, "").toString().isEmpty())
                            throw new IOException("manifest Class-Path in " + entry.getFileName());
                        jar.stream().forEach(e -> description(names, e.getName()));
                    }
                }
            }
        } catch (IOException | InvalidPathException e) {
            throw HostError.bad("stageMismatch", e.getMessage());
        }
        return new Launch(HexFormat.of().formatHex(digest.digest()), List.copyOf(names));
    }

    private static void description(Set<String> names, String resource) {
        // D4: hash every copy, then let the verified class path resolve the first resource.
        if (resource.matches("META-INF/joust/[^/]+\\.json")) names.add(resource);
    }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static void line(MessageDigest manifest, int ordinal, String kind, String name, Path file) throws IOException {
        MessageDigest hash = sha();
        try (var in = new DigestInputStream(Files.newInputStream(file), hash)) { in.transferTo(OutputStream.nullOutputStream()); }
        manifest.update(utf8(HexFormat.of().formatHex(hash.digest()) + " " + ordinal + " " + kind + " " + name + "\n"));
    }

    static String runtimeVersion(ClassLoader loader) {
        try {
            Class<?> runtime = Class.forName("org.julclang.state.runtime.StateRuntime", false, loader);
            Class<?> marker = Class.forName("org.julclang.state.runtime.RuntimeVersion", false, loader);
            if (runtime.getProtectionDomain().getCodeSource().equals(marker.getProtectionDomain().getCodeSource())
                    && HostVersion.RUNTIME.equals(marker.getField("VERSION").get(null))) return HostVersion.RUNTIME;
        } catch (ReflectiveOperationException | LinkageError ignored) {}
        throw HostError.unsupported("runtimeVersion", "this host requires julc-state-runtime " + HostVersion.RUNTIME);
    }
}
