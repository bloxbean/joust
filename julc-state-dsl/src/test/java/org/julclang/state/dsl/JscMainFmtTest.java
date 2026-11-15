package org.julclang.state.dsl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-0021 r9 §15 (M5d): {@code JscMain --fmt [--check]}, the Gradle path's front end of the formatter. */
class JscMainFmtTest {

    static final String FORMATTED = "jsc 1\npackage t\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n}\n";

    @Test
    void formatsInPlace_checks_andLeavesARefusedFileAsItWas(@TempDir Path dir) throws Exception {
        Path messy = dir.resolve("C.jsc");
        Files.writeString(messy, FORMATTED.replace("\n", "\r\n").replace("  ", "\t"));
        Path clean = dir.resolve("sub/D.jsc");
        Files.createDirectories(clean.getParent());
        Files.writeString(clean, FORMATTED.replace("C {", "D {"));
        String broken = "jsc 1\npackage t\n\ncontract E {\n  state { n: Int \n";
        Path bad = dir.resolve("E.jsc");
        Files.writeString(bad, broken);
        long cleanStamp = Files.getLastModifiedTime(clean).toMillis();

        // --check: nothing written; a refused file is exit 2 (#120 F5), over the unformatted one's 1.
        String before = Files.readString(messy);
        assertEquals(JscMain.FMT_REFUSED, JscMain.run(new String[]{"--fmt", "--check", dir.toString()}));
        assertEquals(before, Files.readString(messy));
        // --fmt: the messy file formatted, the clean one untouched, the broken one left as it was (F4): exit 2.
        assertEquals(JscMain.FMT_REFUSED, JscMain.run(new String[]{"--fmt", dir.toString()}));
        assertEquals(FORMATTED, Files.readString(messy));
        assertEquals(cleanStamp, Files.getLastModifiedTime(clean).toMillis());
        assertEquals(broken, Files.readString(bad));
        // Without the broken file: an unformatted file under --check is 1, a formatted tree 0.
        Files.delete(bad);
        Files.writeString(messy, before);
        assertEquals(JscMain.FMT_NOT_FORMATTED, JscMain.run(new String[]{"--fmt", "--check", dir.toString()}));
        assertEquals(JscMain.FMT_OK, JscMain.run(new String[]{"--fmt", messy.toString()}));
        assertEquals(JscMain.FMT_OK, JscMain.run(new String[]{"--fmt", "--check", dir.toString()}));
        try (var s = Files.walk(dir)) {
            assertEquals(0, s.filter(p -> p.getFileName().toString().endsWith(".fmt")).count());
        }
        assertEquals(JscMain.FMT_USAGE, JscMain.run(new String[]{"--fmt"}));
        assertEquals(JscMain.FMT_USAGE, JscMain.run(new String[]{"--fmt", dir.resolve("missing.jsc").toString()}));
    }

    /** #120 F1: a file that is not valid UTF-8 is refused and left byte for byte; the run goes on with the others. */
    @Test
    void invalidUtf8IsRefusedThatFileOnly(@TempDir Path dir) throws Exception {
        byte[] latin1Comment = (FORMATTED.replace("  state", "// café latin-1\n    state")).getBytes(StandardCharsets.ISO_8859_1);
        byte[] latin1String = FORMATTED.replace("return { n: 0 }", "require(true, \"café\")\n    return { n: 0 }").getBytes(StandardCharsets.ISO_8859_1);
        Path a = dir.resolve("A.jsc");
        Path b = dir.resolve("B.jsc");
        Path c = dir.resolve("Z.jsc");
        Files.write(a, latin1Comment);
        Files.write(b, latin1String);
        Files.writeString(c, FORMATTED.replace("    return", "        return"));
        assertEquals(JscMain.FMT_REFUSED, JscMain.run(new String[]{"--fmt", dir.toString()}));
        assertArrayEquals(latin1Comment, Files.readAllBytes(a));
        assertArrayEquals(latin1String, Files.readAllBytes(b));
        assertEquals(FORMATTED, Files.readString(c), "the later file is still formatted");
    }

    /** #120 F2, S2: a rewritten file keeps its permissions; a read-only one is refused, never made writable. */
    @Test
    void permissionsAreKept_readOnlyIsRefused_aLinkedRootIsRefused(@TempDir Path dir) throws Exception {
        Path shared = dir.resolve("S.jsc");
        Path group = dir.resolve("G.jsc");
        Path ro = dir.resolve("R.jsc");
        String messy = FORMATTED.replace("    return", "        return");
        for (Path p : new Path[]{shared, group, ro}) Files.writeString(p, messy);
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(group, PosixFilePermissions.fromString("rw-rw----"));
        Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("r--r--r--"));
        assertEquals(JscMain.FMT_REFUSED, JscMain.run(new String[]{"--fmt", dir.toString()}));
        assertEquals(FORMATTED, Files.readString(shared));
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(shared)));
        assertEquals(FORMATTED, Files.readString(group));
        assertEquals("rw-rw----", PosixFilePermissions.toString(Files.getPosixFilePermissions(group)));
        assertEquals(messy, Files.readString(ro), "a read-only file is left as it was");
        assertEquals("r--r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(ro)));
        // A linked directory argument is refused (joust fmt refuses one too).
        Path link = dir.resolveSibling(dir.getFileName() + "-link");
        Files.createSymbolicLink(link, dir);
        try {
            assertEquals(JscMain.FMT_USAGE, JscMain.run(new String[]{"--fmt", link.toString()}));
        } finally {
            Files.delete(link);
        }
    }

    /** #120 C3: a file is formatted as a module of its directory argument: an imported enum's variants parse. */
    @Test
    void aFileIsFormattedWithItsImports(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("fees.jsc"), "jsc 1\nlibrary t\n\nenum Tier { Basic, Gold { since: Int } }\n");
        String shop = "jsc 1\npackage t\n\nimport { Tier } from \"./fees.jsc\"\n\ncontract S {\n  state { t: Tier }\n\n  init() {\n    return { t: Gold { since: 1 } }\n  }\n}\n";
        Path s = dir.resolve("S.jsc");
        Files.writeString(s, shop.replace("    return", "      return"));
        assertEquals(JscMain.FMT_OK, JscMain.run(new String[]{"--fmt", dir.toString()}));
        assertEquals(shop, Files.readString(s));
        // Its import broken (no such file): the variant cannot be known, so the file is refused rather than guessed.
        Files.delete(dir.resolve("fees.jsc"));
        Files.writeString(s, shop.replace("    return", "      return"));
        assertEquals(JscMain.FMT_REFUSED, JscMain.run(new String[]{"--fmt", s.toString()}));
    }
}
