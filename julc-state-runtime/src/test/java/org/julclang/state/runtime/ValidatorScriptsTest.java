package org.julclang.state.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1c review S1/F1: a validator's script is the one beside its class, never another validator's of the same
 * simple name; when that cannot be told, the runtime refuses rather than take the first copy on the class path.
 */
class ValidatorScriptsTest {

    static final String RESOURCE = "META-INF/plutus/VaultValidator.plutus.json";

    static Path classes(Path root, String name, String cbor, String... classFiles) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Path r = dir.resolve(RESOURCE);
        Files.createDirectories(r.getParent());
        Files.writeString(r, "{ \"type\": \"PlutusScriptV3\", \"description\": \"VaultValidator\", \"cborHex\": \"" + cbor + "\" }");
        for (String c : classFiles) {
            Path f = dir.resolve(c);
            Files.createDirectories(f.getParent());
            Files.write(f, new byte[]{(byte) 0xCA, (byte) 0xFE});
        }
        return dir;
    }

    static String cbor(String json) {
        var m = java.util.regex.Pattern.compile("\"cborHex\": \"([0-9a-f]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }

    @Test
    void theCopyBesideTheClass_winsOverTheFirstOnTheClassPath(@TempDir Path tmp) throws Exception {
        Path javaDir = classes(tmp, "java", "aa01", "org/x/vault/VaultValidator.class");
        Path port = classes(tmp, "port", "bb02", "org/x/dsl/VaultValidator.class");
        // The class path lists the Java example first: a lookup by name would give its script to both.
        try (var loader = new URLClassLoader(new URL[]{javaDir.toUri().toURL(), port.toUri().toURL()}, null)) {
            assertEquals(2, Collections.list(loader.getResources(RESOURCE)).size());
            assertEquals("bb02", cbor(ValidatorScripts.resolve("org.x.dsl.VaultValidator", "VaultValidator", port.toUri().toURL(), loader)));
            assertEquals("aa01", cbor(ValidatorScripts.resolve("org.x.vault.VaultValidator", "VaultValidator", javaDir.toUri().toURL(), loader)));
        }
    }

    @Test
    void aJarCodeSource_isReadToo(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("port.jar");
        try (var out = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            out.putNextEntry(new JarEntry(RESOURCE));
            out.write("{ \"cborHex\": \"cc03\" }".getBytes());
            out.putNextEntry(new JarEntry("org/x/dsl/VaultValidator.class"));
            out.write(new byte[]{1});
        }
        Path other = classes(tmp, "java", "aa01", "org/x/vault/VaultValidator.class");
        try (var loader = new URLClassLoader(new URL[]{other.toUri().toURL(), jar.toUri().toURL()}, null)) {
            assertEquals("cc03", cbor(ValidatorScripts.resolve("org.x.dsl.VaultValidator", "VaultValidator", jar.toUri().toURL(), loader)));
        }
    }

    @Test
    void ambiguity_isRefused(@TempDir Path tmp) throws Exception {
        Path javaDir = classes(tmp, "java", "aa01");
        Path port = classes(tmp, "port", "bb02");
        try (var loader = new URLClassLoader(new URL[]{javaDir.toUri().toURL(), port.toUri().toURL()}, null)) {
            // No code source (or one without the resource) and two copies on the class path: refused, not the first.
            var e = assertThrows(JoustException.class, () -> ValidatorScripts.resolve("org.x.dsl.VaultValidator", "VaultValidator", null, loader));
            assertTrue(e.getMessage().contains("2 copies"), e.getMessage());
            Path elsewhere = Files.createDirectories(tmp.resolve("empty"));
            assertThrows(JoustException.class, () -> ValidatorScripts.resolve("org.x.dsl.VaultValidator", "VaultValidator", elsewhere.toUri().toURL(), loader));
        }
        // A single copy on the class path is used when the code source cannot be read.
        try (var loader = new URLClassLoader(new URL[]{port.toUri().toURL()}, null)) {
            assertEquals("bb02", cbor(ValidatorScripts.resolve("org.x.dsl.VaultValidator", "VaultValidator", null, loader)));
        }
        // A shaded code source holding two validators of that name: one script overwrote the other, so refuse.
        Path shaded = classes(tmp, "shaded", "dd04", "org/x/vault/VaultValidator.class", "org/x/dsl/VaultValidator.class");
        try (var loader = new URLClassLoader(new URL[]{shaded.toUri().toURL()}, null)) {
            var e = assertThrows(JoustException.class, () -> ValidatorScripts.resolve("org.x.dsl.VaultValidator", "VaultValidator", shaded.toUri().toURL(), loader));
            assertTrue(e.getMessage().contains("holds 2 validators named VaultValidator"), e.getMessage());
        }
        // No copy at all.
        try (var loader = new URLClassLoader(new URL[0], null)) {
            assertThrows(JoustException.class, () -> ValidatorScripts.resolve("org.x.dsl.VaultValidator", "VaultValidator", null, loader));
        }
    }
}
