package org.julclang.state.gradle;

import groovy.json.JsonOutput;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The client reads this file only after a successful joustHostSpec invocation. */
@UntrackedTask(because = "Recheck the actual ordered files and runtime metadata for every explicit launch")
public abstract class HostSpec extends DefaultTask {
    @Internal public abstract ConfigurableFileCollection getApplication();
    @Internal public abstract ConfigurableFileCollection getOptionalOutputs();
    @Internal public abstract ConfigurableFileCollection getRequiredArtifacts();
    @Internal public abstract ListProperty<File> getExtraJars();
    @Internal public abstract Property<String> getJavaExecutable();
    @OutputFile public abstract RegularFileProperty getSpec();

    @TaskAction public void write() throws IOException {
        // First, so a launch directory that would contain the spec exists and is checked on the first run too.
        Files.createDirectories(getSpec().get().getAsFile().toPath().getParent());
        var optional = getOptionalOutputs().getFiles();
        var required = getRequiredArtifacts().getFiles();
        var entries = new ArrayList<File>();
        for (File file : getApplication()) {
            if (optional.contains(file) && !required.contains(file) && Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            entries.add(file);
        }
        entries.addAll(getExtraJars().get());
        writeSpec(getJavaExecutable().get(), entries, getSpec().get().getAsFile().toPath());
    }

    static void writeSpec(String java, List<File> entries, Path output) throws IOException {
        // The spec must not be inside a launch directory: writing it would change the directory just hashed, so the
        // client's verification fails on every run while Gradle reports success (#150 Codex review).
        Files.createDirectories(output.getParent());
        Path parent = output.getParent().toRealPath();
        for (File file : entries) {
            Path dir = file.toPath();
            if (!Files.isDirectory(dir)) continue;
            if (parent.startsWith(dir.toRealPath())) {
                throw new org.gradle.api.GradleException("the host spec " + output + " is inside the launch directory " + file
                        + ": move the directory out of " + output.getParent() + ", or the spec cannot be verified");
            }
        }
        var manifest = LaunchManifest.scan(entries);
        var spec = Map.of("protocol", 1, "java", java, "mainClass", "org.julclang.state.host.DevHost",
                "launch", manifest.entries(), "digest", manifest.digest());
        Files.createDirectories(output.getParent());
        Path temp = Files.createTempFile(output.getParent(), "host-spec", ".tmp");
        try {
            Files.writeString(temp, JsonOutput.toJson(spec) + "\n");
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
