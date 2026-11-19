package org.julclang.state.gradle;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** D4's byte format, mirrored by Launch.scan in the separately published JVM host. */
record LaunchManifest(List<Map<String, Object>> entries, String digest) {
    static LaunchManifest scan(List<File> files) throws IOException {
        var fingerprint = sha256();
        var entries = new ArrayList<Map<String, Object>>();
        for (File file : files) {
            Path path = file.toPath().toRealPath();
            int ordinal = entries.size();
            if (Files.isDirectory(path)) {
                var hashes = new TreeMap<String, String>((a, b) -> Arrays.compareUnsigned(utf8(a), utf8(b)));
                try (var paths = Files.walk(path)) {
                    for (var it = paths.iterator(); it.hasNext();) {
                        Path child = it.next();
                        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                        if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Non-regular file: " + child);
                        hashes.put(path.relativize(child).toString().replace(File.separatorChar, '/'), hash(child));
                    }
                }
                hashes.forEach((name, hash) -> line(fingerprint, hash, ordinal, "dir", name));
                entries.add(Map.of("ordinal", ordinal, "kind", "dir", "path", file.getAbsolutePath(), "files", hashes));
            } else {
                if (!Files.isRegularFile(path)) throw new IOException("Non-regular jar: " + path);
                try (var jar = new JarFile(file, false)) {
                    var manifest = jar.getManifest();
                    if (manifest != null && !manifest.getMainAttributes().getOrDefault(Attributes.Name.CLASS_PATH, "").equals("")) {
                        throw new IOException("manifest Class-Path in " + file);
                    }
                }
                String hash = hash(path);
                line(fingerprint, hash, ordinal, "jar", "");
                entries.add(Map.of("ordinal", ordinal, "kind", "jar", "path", file.getAbsolutePath(), "sha256", hash));
            }
        }
        return new LaunchManifest(entries, HexFormat.of().formatHex(fingerprint.digest()));
    }

    private static void line(MessageDigest digest, String hash, int ordinal, String kind, String name) {
        digest.update(utf8(hash + " " + ordinal + " " + kind + " " + name + "\n"));
    }

    private static String hash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
