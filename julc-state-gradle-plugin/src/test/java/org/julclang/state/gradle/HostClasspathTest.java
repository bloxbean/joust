package org.julclang.state.gradle;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

/** HostClasspath's class checks on their own, without a Gradle build (#150 reviews). */
class HostClasspathTest {
    @TempDir Path temp;

    private File jar(String name, String... entries) throws Exception {
        Path p = temp.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(p))) {
            for (String e : entries) {
                out.putNextEntry(new ZipEntry(e));
                out.closeEntry();
            }
        }
        return p.toFile();
    }

    @Test void aClassifierOnlyVmIsNotTheVm() throws Exception {
        // #150 Opus P3-2: julc-vm's component is selected, but its jar on the launch path carries no classes (a sources
        // or other classifier artifact): the host could not initialise, so the spec is refused, naming the class.
        var sources = jar("julc-vm-sources.jar", "org/julclang/vm/JulcVmProvider.java");
        var error = assertThrows(GradleException.class, () -> HostClasspath.requireVmProvider(Set.of(sources)));
        assertTrue(error.getMessage().contains("org.julclang.vm.JulcVmProvider"), error.getMessage());
        HostClasspath.requireVmProvider(Set.of(sources, jar("julc-vm.jar", "org/julclang/vm/JulcVmProvider.class")));
        var classes = Files.createDirectories(temp.resolve("classes/org/julclang/vm"));
        Files.createFile(classes.resolve("JulcVmProvider.class"));
        HostClasspath.requireVmProvider(Set.of(temp.resolve("classes").toFile()));
    }

    @Test void aVersionedEntryIsTheClassItNames_onlyInAMultiReleaseJar() throws Exception {
        String cls = "org/julclang/state/serve/ServedContract.class";
        var plain = jar("plain.jar", "META-INF/versions/9/" + cls);
        assertFalse(HostClasspath.overlaps(plain, Set.of(cls)), "not multi-release: the JVM never loads it");
    }
}
