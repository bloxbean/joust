package org.julclang.state.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.julclang.state.dsl.v2.JscV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #121 S5 (the Gradle path, as joust build's): the acceptJoustLock task and --accept-abi write joust.lock and ABI
 * manifests only as regular files, never through a link, through a temporary file renamed over the original.
 */
class JscMainAcceptTest {

    static final List<JscV2.LibraryUse> USED = List.of(new JscV2.LibraryUse("java", "java:com.example.compliance.AllowList", "ab".repeat(32)));

    @Test
    void aLinkedLockIsNotAccepted(@TempDir Path dir) throws Exception {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path project = Files.createDirectories(dir.resolve("project"));
        Path victim = outside.resolve("dot-zshrc");
        Files.writeString(victim, "export PATH=/usr/bin\n");
        Path lock = project.resolve("joust.lock");
        Files.createSymbolicLink(lock, victim);
        assertEquals(1, JscMain.checkLock(lock, USED, true));
        assertEquals("export PATH=/usr/bin\n", Files.readString(victim), "the outside file is untouched");
        assertTrue(Files.isSymbolicLink(lock));
        // A dangling link: nothing created outside.
        Files.delete(lock);
        Path ghost = outside.resolve("created-by-joust");
        Files.createSymbolicLink(lock, ghost);
        assertEquals(1, JscMain.checkLock(lock, USED, true));
        assertFalse(Files.exists(ghost, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void anAcceptedLockReplacesARegularFileKeepingItsMode(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("joust.lock");
        Files.writeString(lock, "{ \"lock\": 1, \"libraries\": {} }\n");
        Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-r-----"));
        assertEquals(0, JscMain.checkLock(lock, USED, true));
        assertTrue(Files.readString(lock).contains("ab".repeat(32)));
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(lock)));
        try (var s = Files.list(dir)) {
            assertEquals(0, s.filter(p -> p.getFileName().toString().endsWith(".tmp")).count());
        }
        assertEquals(0, JscMain.checkLock(lock, USED, false), "the accepted lock holds");
    }

    @Test
    void aLinkedManifestIsNotAccepted(@TempDir Path dir) throws Exception {
        Path victim = Files.writeString(Files.createDirectories(dir.resolve("outside")).resolve("x.json"), "keep");
        Path manifest = Files.createDirectories(dir.resolve("src")).resolve("Shop.abi.json");
        Files.createSymbolicLink(manifest, victim);
        String err = JscMain.checkAbi(manifest, "{\"abi\":1}\n", "Shop.jsc", true);
        assertNotNull(err);
        assertTrue(err.contains("JSC0002") && err.contains("is a link"), err);
        assertEquals("keep", Files.readString(victim));
        // A regular manifest is accepted (and written whole).
        Files.delete(manifest);
        assertNull(JscMain.checkAbi(manifest, "{\"abi\":1}\n", "Shop.jsc", true));
        assertEquals("{\"abi\":1}\n", Files.readString(manifest));
    }
}
