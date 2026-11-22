package org.julclang.state.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LaunchManifestTest {
    @TempDir Path temp;

    @Test void theScriptedClientRefusesASpecialFileBeforeCopying(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        var fifo = dir.resolve("stale.jar");
        assertEquals(0, new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start().waitFor());
        var copy = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { SnapshotClient.copyJar(fifo, dir.resolve("0.jar")); return "copied"; }
            catch (java.io.IOException e) { return e.getMessage(); }
        });
        // A FIFO without a writer would block the copy for ever: refused at once instead (#150 Codex review).
        assertTrue(copy.get(10, java.util.concurrent.TimeUnit.SECONDS).contains("not a regular file"));
    }
    @Test void globalOrdinalsAndUnsignedUtf8Ordering() throws Exception {
        var dir = Files.createDirectory(temp.resolve("classes"));
        var jar = temp.resolve("local.jar");
        HostSpecConsumerTest.jar(jar, null);
        String early = "\ue000", late = "😀";
        Files.writeString(dir.resolve(late), "b");
        Files.writeString(dir.resolve(early), "a");
        String a = hash(new byte[]{'a'}), b = hash(new byte[]{'b'}), j = hash(Files.readAllBytes(jar));
        String lines = a + " 0 dir " + early + "\n" + b + " 0 dir " + late + "\n" + j + " 1 jar \n";
        var manifest = LaunchManifest.scan(List.of(dir.toFile(), jar.toFile()));
        assertEquals(hash(lines.getBytes(StandardCharsets.UTF_8)), manifest.digest());
        assertNotEquals(manifest.digest(), LaunchManifest.scan(List.of(jar.toFile(), dir.toFile())).digest());
    }

    @Test void missingArtifactAndManifestClassPathNameTheOffendingEntry() throws Exception {
        var jar = temp.resolve("file-dependency.jar");
        assertThrows(NoSuchFileException.class, () -> LaunchManifest.scan(List.of(jar.toFile())));
        HostSpecConsumerTest.jar(jar, "extra.jar");
        var error = assertThrows(IOException.class, () -> LaunchManifest.scan(List.of(jar.toFile())));
        assertTrue(error.getMessage().contains("manifest Class-Path") && error.getMessage().contains(jar.toString()));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
