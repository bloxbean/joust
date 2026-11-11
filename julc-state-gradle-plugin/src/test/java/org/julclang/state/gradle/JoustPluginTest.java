package org.julclang.state.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0017 D1: what applying {@code org.julclang.joust} configures (I6, plugin part). */
class JoustPluginTest {

    @TempDir
    Path dir;

    static Properties versions() throws IOException {
        Properties p = new Properties();
        try (var in = JoustPluginTest.class.getResourceAsStream("/META-INF/joust/gradle-plugin.properties")) {
            p.load(in);
        }
        return p;
    }

    Project project() {
        Project p = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        p.getPluginManager().apply(JoustPlugin.ID);
        ((ProjectInternal) p).evaluate();
        return p;
    }

    static List<String> deps(Project p, String configuration) {
        return p.getConfigurations().getByName(configuration).getDependencies().stream()
                .map(d -> d.getGroup() + ":" + d.getName() + (d.getVersion() == null ? "" : ":" + d.getVersion()))
                .sorted().collect(Collectors.toList());
    }

    @org.junit.jupiter.api.Test
    void versionsAreThisBuilds() throws IOException {
        Properties v = versions();
        assertThat(v.getProperty("joust")).isNotBlank().doesNotEndWith("-SNAPSHOT-SNAPSHOT");
        assertThat(v.getProperty("julc")).isNotBlank();
        assertThat(v.getProperty("ccl")).isNotBlank();
        // The effective version (commit-qualified for snapshots), not gradle.properties' raw value.
        assertThat(v.getProperty("joust")).isEqualTo(System.getProperty("joust.version"));
    }

    @org.junit.jupiter.api.Test
    void dependenciesAtThePluginsVersions() throws IOException {
        Properties v = versions();
        String j = v.getProperty("joust");
        Project p = project();
        assertThat(deps(p, "implementation")).containsExactly(
                "com.bloxbean.cardano:cardano-client-backend-blockfrost:" + v.getProperty("ccl"),
                "org.julclang:julc-state-runtime:" + j);
        assertThat(deps(p, "annotationProcessor")).containsExactly(
                "org.julclang:julc-annotation-processor:" + v.getProperty("julc"),
                "org.julclang:julc-state-api:" + j,
                "org.julclang:julc-state-processor:" + j);
        Dependency onchain = p.getConfigurations().getByName("annotationProcessor").getAllDependencies().stream()
                .filter(d -> d.getName().equals("julc-state-api")).findFirst().orElseThrow();
        assertThat(((org.gradle.api.artifacts.ModuleDependency) onchain).getArtifacts())
                .extracting(a -> a.getClassifier()).containsExactly("onchain");
        assertThat(deps(p, "testImplementation")).containsExactly("org.julclang:julc-state-testkit:" + j);
        assertThat(deps(p, "testRuntimeOnly")).containsExactly("org.junit.platform:junit-platform-launcher");
    }

    @org.junit.jupiter.api.Test
    void blockfrostCanBeLeftOut() {
        Project p = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        p.getPluginManager().apply(JoustPlugin.ID);
        p.getExtensions().getByType(JoustExtension.class).getBlockfrost().set(false);
        ((ProjectInternal) p).evaluate();
        assertThat(deps(p, "implementation")).noneMatch(d -> d.contains("blockfrost"));
    }

    @org.junit.jupiter.api.Test
    void compileTestAndRunSettings() {
        Project p = project();
        assertThat(p.getExtensions().getByType(JavaPluginExtension.class).getToolchain().getLanguageVersion().get()
                .asInt()).isEqualTo(25);
        JavaCompile compile = (JavaCompile) p.getTasks().getByName("compileJava");
        assertThat(compile.getOptions().getCompilerArgs()).containsOnlyOnce("-parameters");
        assertThat(compile.getOptions().getEncoding()).isEqualTo("UTF-8");
        Test test = (Test) p.getTasks().getByName("test");
        assertThat(test.getJvmArgs()).contains("--enable-native-access=ALL-UNNAMED");
        assertThat(test.getSystemProperties()).containsEntry("joust.mapStore", "memory");
        JavaExec run = p.getTasks().register("demo", JavaExec.class).get();
        assertThat(run.getJvmArgs()).contains("--enable-native-access=ALL-UNNAMED");
        assertThat(p.getTasks().findByName("generateJsc")).isNull();
    }

    @org.junit.jupiter.api.Test
    void jscWhenThereAreJscSources() throws IOException {
        Files.createDirectories(dir.resolve("src/main/jsc/app"));
        Project p = project();
        JavaExec gen = (JavaExec) p.getTasks().getByName("generateJsc");
        assertThat(gen.getMainClass().get()).isEqualTo("org.julclang.state.dsl.JscMain");
        assertThat(deps(p, "joustJsc")).containsExactly("org.julclang:julc-state-dsl:" + versions().getProperty("joust"));
        var main = p.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName("main");
        assertThat(main.getJava().getSrcDirs()).contains(
                p.getLayout().getBuildDirectory().dir("generated/sources/jsc/java/main").get().getAsFile());
    }

    // ---- TestKit: a real Gradle build of a project applying the plugin (no dependency resolution needed)

    GradleRunner runner(String buildGradle, String... args) throws IOException {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'app'\n");
        Files.writeString(dir.resolve("build.gradle"), buildGradle);
        return GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withArguments(args);
    }

    @org.junit.jupiter.api.Test
    void aToolchainBelow25IsRefused() throws IOException {
        BuildResult r = runner("""
                plugins { id 'org.julclang.joust' }
                java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
                """, "help").buildAndFail();
        assertThat(r.getOutput()).contains("JOUST needs a Java 25 toolchain").contains("Java 21");
    }

    @org.junit.jupiter.api.Test
    void configurationCacheCompatible() throws IOException {
        Files.createDirectories(dir.resolve("src/main/jsc/app"));
        String build = "plugins { id 'org.julclang.joust' }\n";
        BuildResult first = runner(build, "tasks", "--configuration-cache").build();
        assertThat(first.getOutput()).contains("generateJsc").contains("Configuration cache entry stored");
        BuildResult second = runner(build, "tasks", "--configuration-cache").build();
        assertThat(second.getOutput()).contains("Configuration cache entry reused");
    }
}
