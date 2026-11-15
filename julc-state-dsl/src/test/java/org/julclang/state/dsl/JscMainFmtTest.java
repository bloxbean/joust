package org.julclang.state.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
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

        // --check: nothing written; the unformatted file and the refused one make it fail.
        String before = Files.readString(messy);
        assertEquals(1, JscMain.run(new String[]{"--fmt", "--check", dir.toString()}));
        assertEquals(before, Files.readString(messy));
        // --fmt: the messy file formatted, the clean one untouched, the broken one left as it was (F4).
        assertEquals(1, JscMain.run(new String[]{"--fmt", dir.toString()}));
        assertEquals(FORMATTED, Files.readString(messy));
        assertEquals(cleanStamp, Files.getLastModifiedTime(clean).toMillis());
        assertEquals(broken, Files.readString(bad));
        // Without the broken file: formatted, and --check passes.
        Files.delete(bad);
        assertEquals(0, JscMain.run(new String[]{"--fmt", "--check", dir.toString()}));
        assertEquals(0, JscMain.run(new String[]{"--fmt", messy.toString()}));
        // No leftovers from the atomic write.
        try (var s = Files.walk(dir)) {
            assertEquals(0, s.filter(p -> p.getFileName().toString().endsWith(".fmt")).count());
        }
        assertEquals(2, JscMain.run(new String[]{"--fmt"}));
        assertEquals(2, JscMain.run(new String[]{"--fmt", dir.resolve("missing.jsc").toString()}));
    }
}
