package org.julclang.state.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-0022 §8. A consumer of the binary publications, including its own processor-generated sibling client. */
class HostSpecConsumerTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String CONTRACT = "org.julclang.state.examples.counter.Counter";
    static final String PACKAGE = "org/julclang/state/examples/counter/";
    static final String VERSION = System.getProperty("joust.version");
    @TempDir static Path testKit;
    @TempDir Path temp;
    Path project, messageMap;

    @BeforeEach void consumer() throws Exception {
        project = Files.createDirectory(temp.resolve("app")).toRealPath();
        Path sibling = Files.createDirectory(project.resolve("sibling"));
        fixture("settings.gradle", project.resolve("settings.gradle"));
        fixture("build.gradle", project.resolve("build.gradle"));
        fixture("sibling.gradle", sibling.resolve("build.gradle"));
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("src/main/java/App.java"), "class App {}");
        Path src = Files.createDirectories(sibling.resolve("src/main/java/" + PACKAGE));
        Files.copy(Path.of(System.getProperty("joust.hostSpec.counter")), src.resolve("Counter.java"));
        messageMap = Files.createDirectories(project.resolve("maps/" + PACKAGE)).resolve("Counter.jscmap.json");
        Files.writeString(messageMap, "{\"revision\":0}");
        jar(project.resolve("local.jar"), null);
        module("fixture", "forced", "1");
        module("fixture", "forced", "2");
        module("fixture", "replacement", "1");
        module("org.julclang", "julc-state-serve", "old");
        // The old adapter has the adapter's classes: on the launch path ahead of the plugin's, the JVM would load them.
        try (var out = new JarOutputStream(Files.newOutputStream(project.resolve(
                "fixture-repo/org/julclang/julc-state-serve/old/julc-state-serve-old.jar")))) {
            out.putNextEntry(new java.util.zip.ZipEntry("org/julclang/state/serve/ServedContract.class"));
            out.closeEntry();
        }
        // A partial copy: only one older adapter class, which would still win class lookup for it.
        module("fixture", "partial", "1");
        try (var out = new JarOutputStream(Files.newOutputStream(project.resolve("fixture-repo/fixture/partial/1/partial-1.jar")))) {
            out.putNextEntry(new java.util.zip.ZipEntry("org/julclang/state/serve/JsonValues.class"));
            out.closeEntry();
        }
        module("org.julclang", "julc-state-runtime", "old");
    }

    static void fixture(String name, Path dest) throws IOException {
        try (var in = HostSpecConsumerTest.class.getResourceAsStream("/host-spec/" + name)) {
            Files.copy(Objects.requireNonNull(in), dest);
        }
    }

    void module(String group, String name, String version) throws IOException {
        var dir = Files.createDirectories(project.resolve("fixture-repo").resolve(group.replace('.', '/')).resolve(name).resolve(version));
        jar(dir.resolve(name + "-" + version + ".jar"), null);
        Files.writeString(dir.resolve(name + "-" + version + ".pom"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>%s</groupId>
                <artifactId>%s</artifactId><version>%s</version></project>
                """.formatted(group, name, version));
    }

    static void jar(Path path, String classPath) throws IOException {
        var mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classPath != null) mf.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        try (var ignored = new JarOutputStream(Files.newOutputStream(path), mf)) {}
    }

    GradleRunner runner(String... tasks) {
        var args = new ArrayList<>(List.of(tasks));
        args.add("-PtestRepo=" + System.getProperty("joust.hostSpec.repository"));
        args.add("-PjoustVersion=" + VERSION);
        return GradleRunner.create().withTestKitDir(testKit.toFile()).withProjectDir(project.toFile()).withPluginClasspath().withArguments(args);
    }

    JsonNode spec(boolean http) throws Exception {
        var args = new ArrayList<>(List.of(":applicationClasspath", ":generatePomFileForAppPublication",
                ":generateMetadataFileForAppPublication"));
        if (http) args.add("-Phttp");
        var build = runner(http ? new String[]{":joustHostSpec", "-Phttp"} : new String[]{":joustHostSpec"}).build();
        assertNotNull(build.task(":sibling:jar"));
        assertNotNull(build.task(":messageMaps"));
        runner(args.toArray(String[]::new)).build();
        assertFalse(build.getOutput().contains("\"launch\":"), build.getOutput());
        var spec = JSON.readTree(project.resolve("build/joust/host-spec.json").toFile());
        List<String> app = Files.readAllLines(project.resolve("build/expected-classpath"));
        assertTrue(app.contains(project.resolve("build/custom/classes").toString()));
        assertFalse(Files.exists(project.resolve("build/custom/resources")));
        assertTrue(app.contains(project.resolve("local.jar").toString()));
        var names = app.stream().map(p -> Path.of(p).getFileName().toString()).toList();
        assertTrue(names.containsAll(List.of("forced-2.jar", "replacement-1.jar")));
        assertFalse(names.contains("forced-1.jar"));
        assertTrue(names.stream().anyMatch(s -> s.startsWith("sibling") && s.endsWith(".jar")));
        assertFalse(names.stream().anyMatch(s -> s.contains("blockfrost") || s.startsWith("julc-state-host-")));
        String serve = "julc-state-serve-" + VERSION + ".jar";
        assertEquals(http, names.contains(serve));
        List<String> actual = spec.required("launch").findValuesAsText("path");
        assertEquals(app, actual.subList(0, app.size()));
        var extra = actual.subList(app.size(), actual.size()).stream().map(p -> Path.of(p).getFileName().toString()).toList();
        String host = "julc-state-host-" + VERSION + ".jar";
        assertEquals(http ? List.of(host) : List.of(serve, host), extra);
        for (String file : List.of("pom-default.xml", "module.json")) {
            String publication = Files.readString(project.resolve("build/publications/app/" + file));
            assertTrue(publication.contains("julc-state-runtime"));
            // Whole module names: an HTTP-server consumer's own publication names julc-state-server, which starts with
            // "julc-state-serve".
            assertFalse(java.util.regex.Pattern.compile("julc-state-host\\b").matcher(publication).find(), file);
            assertFalse(java.util.regex.Pattern.compile("julc-state-serve\\b").matcher(publication).find(), file);
        }
        return spec;
    }

    @Test void launchesExactlyTheApplicationGraphAndSurvivesSourceCleanup() throws Exception {
        var spec = spec(false);
        SnapshotClient.describe(project, temp, spec, () -> {
            // First message-map load is in describe. Replace its source twice, clean both output trees,
            // and evict the fixture's Maven artifact from its private file repository.
            for (int i = 1; i <= 2; ++i) {
                Files.writeString(messageMap, "{\"revision\":" + i + "}");
                runner(":messageMaps", ":sibling:jar").build();
            }
            runner(":clean", ":sibling:clean").build();
            Files.delete(project.resolve("local.jar"));
            Path artifact = spec.required("launch").findValuesAsText("path").stream().map(Path::of)
                    .filter(p -> p.getFileName().toString().equals("replacement-1.jar")).findFirst().orElseThrow().toRealPath();
            assertTrue(artifact.startsWith(project.resolve("fixture-repo")) || artifact.startsWith(testKit.toRealPath()));
            Files.delete(artifact);
        });
    }

    @Test void normalBuildDoesNotResolveOrPublishTheHost() throws Exception {
        Files.writeString(project.resolve("build.gradle"), """
                configurations.joustHost.incoming.beforeResolve { throw new GradleException("resolved host during assemble") }
                """, StandardOpenOption.APPEND);
        var build = runner(":assemble", ":generatePomFileForAppPublication").build();
        assertNull(build.task(":joustHostSpec"));
        assertFalse(Files.exists(project.resolve("build/joust/host-spec.json")));
        assertFalse(Files.readString(project.resolve("build/publications/app/pom-default.xml")).contains("julc-state-host"));
    }

    @Test void httpAdapterIsReusedOnlyAtThePluginVersion() throws Exception {
        SnapshotClient.describe(project, temp, spec(true), () -> {});
        var error = runner(":joustHostSpec", "-Phttp", "-Pincompatible").buildAndFail().getOutput();
        assertTrue(error.contains("incompatible adapter") && error.contains("julc-state-serve:old"), error);
    }

    @Test void theAdapterThatWinsClassLookupIsTheOneChecked() throws Exception {
        // #150 Codex review: an older adapter through another configuration comes first on the launch path.
        String error = runner(":joustHostSpec", "-PsecondAdapter").buildAndFail().getOutput();   // =old
        assertTrue(error.contains("incompatible adapter") && error.contains("julc-state-serve-old.jar")
                && error.contains("provides julc-state-serve classes"), error);
        error = runner(":joustHostSpec", "-PpartialAdapter").buildAndFail().getOutput();
        assertTrue(error.contains("incompatible adapter") && error.contains("partial-1.jar") && error.contains("fixture:partial:1"), error);
        // The plugin's own adapter binary through another configuration is the adapter: accepted, and not added twice.
        runner(":joustHostSpec", "-PsecondAdapter=current").build();
        var twice = JSON.readTree(project.resolve("build/joust/host-spec.json").toFile()).required("launch").findValuesAsText("path")
                .stream().filter(p -> Path.of(p).getFileName().toString().equals("julc-state-serve-" + VERSION + ".jar")).count();
        assertEquals(1, twice);
        // A sources jar of the right version is not the adapter: the plugin's binary adapter is added.
        runner(":joustHostSpec", "-PsourcesAdapter").build();
        var spec = JSON.readTree(project.resolve("build/joust/host-spec.json").toFile());
        var names = spec.required("launch").findValuesAsText("path").stream().map(p -> Path.of(p).getFileName().toString()).toList();
        assertTrue(names.contains("julc-state-serve-" + VERSION + ".jar") && names.contains("julc-state-serve-" + VERSION + "-sources.jar"), names.toString());
        assertTrue(names.indexOf("julc-state-serve-" + VERSION + "-sources.jar") < names.indexOf("julc-state-serve-" + VERSION + ".jar"));
    }
    @Test void theHostsVmDependencyAndTheSpecsLocationAreChecked() throws Exception {
        String error = runner(":joustHostSpec", "-PnoVm").buildAndFail().getOutput();
        assertTrue(error.contains("Unsatisfied runtime dependency") && error.contains("julc-vm"), error);
        error = runner(":joustHostSpec", "-PspecInOutput").buildAndFail().getOutput();
        assertTrue(error.contains("is inside the launch directory"), error);
    }
    @Test void rejectsIncompleteGraphsAndUnpinnedJarEntries() throws Exception {
        String error = runner(":joustHostSpec", "-PmissingJackson").buildAndFail().getOutput();
        assertTrue(error.contains("Unsatisfied runtime dependency") && error.contains("jackson-databind"), error);
        error = runner(":joustHostSpec", "-PbadRuntime").buildAndFail().getOutput();
        assertTrue(error.contains("julc-state-runtime") && error.contains("selected at old"), error);
        error = runner(":joustHostSpec", "-PmissingFile").buildAndFail().getOutput();
        assertTrue(error.contains("missing.jar"), error);
        jar(project.resolve("local.jar"), "other.jar");
        error = runner(":joustHostSpec").buildAndFail().getOutput();
        assertTrue(error.contains("manifest Class-Path") && error.contains("local.jar"), error);
    }
}
