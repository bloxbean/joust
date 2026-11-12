package org.julclang.state.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

/**
 * {@code org.julclang.joust}: everything a JOUST project needs to build, test and run (ADR-0017 D1).
 *
 * <ul>
 *   <li>the {@code java} plugin, with a Java 25 toolchain by convention (the JOUST processor is Java 25 bytecode);</li>
 *   <li>the runtime, the Blockfrost backend (DevKit), the testkit and the JUnit Platform launcher;</li>
 *   <li>the JOUST processor, JuLC's processor and the on-chain API sources on the annotation processor path;</li>
 *   <li>{@code -parameters}, UTF-8, native access for tests and {@code run}, MerkleMap mirrors in memory for tests;</li>
 *   <li>{@code generateJsc}, when {@code src/main/jsc} exists: {@code .jsc} lowered to Java before compiling.</li>
 * </ul>
 *
 * <p>Every version comes from the JOUST build that produced the plugin ({@code META-INF/joust/gradle-plugin.properties}),
 * so upgrading a project is the plugin's version. The plugin adds no repositories.
 */
public class JoustPlugin implements Plugin<Project> {

    public static final String ID = "org.julclang.joust";
    static final int JAVA = 25;
    static final String MAIN_JSC = "src/main/jsc";

    @Override
    public void apply(Project project) {
        Versions versions = Versions.load();
        project.getPluginManager().apply(JavaPlugin.class);
        JoustExtension joust = project.getExtensions().create("joust", JoustExtension.class);
        joust.getBlockfrost().convention(true);

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().convention(JavaLanguageVersion.of(JAVA));
        project.afterEvaluate(p -> {
            JavaLanguageVersion v = java.getToolchain().getLanguageVersion().getOrNull();
            if (v != null && !v.canCompileOrRun(JAVA)) {
                throw new GradleException("JOUST needs a Java " + JAVA + " toolchain (its processor is Java " + JAVA
                        + " bytecode), but this project's toolchain is Java " + v.asInt() + ". Remove the toolchain"
                        + " setting, or set it to " + JAVA + " or later.");
            }
        });

        var deps = project.getDependencies();
        String jv = versions.joust();
        deps.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, "org.julclang:julc-state-runtime:" + jv);
        Provider<List<Dependency>> blockfrost = joust.getBlockfrost().map(on -> on
                ? List.of(deps.create("com.bloxbean.cardano:cardano-client-backend-blockfrost:" + versions.ccl()))
                : List.of());
        project.getConfigurations().named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME)
                .configure(c -> c.getDependencies().addAllLater(blockfrost));
        deps.add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, "org.julclang:julc-state-processor:" + jv);
        deps.add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME,
                "org.julclang:julc-annotation-processor:" + versions.julc());
        deps.add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, "org.julclang:julc-state-api:" + jv + ":onchain");
        deps.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME, "org.julclang:julc-state-testkit:" + jv);
        deps.add(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME, "org.junit.platform:junit-platform-launcher");

        project.getTasks().withType(JavaCompile.class).configureEach(t -> {
            t.getOptions().setEncoding("UTF-8");
            if (!t.getOptions().getCompilerArgs().contains("-parameters")) {
                t.getOptions().getCompilerArgs().add("-parameters");
            }
        });
        project.getTasks().withType(Test.class).configureEach(t -> {
            t.useJUnitPlatform();
            t.jvmArgs("--enable-native-access=ALL-UNNAMED");
            t.systemProperty("joust.mapStore", "memory");
        });
        project.getTasks().withType(JavaExec.class).configureEach(t -> t.jvmArgs("--enable-native-access=ALL-UNNAMED"));

        if (project.file(MAIN_JSC).isDirectory()) {
            jsc(project, versions);
        }
    }

    /** {@code .jsc} lowered to Java (ADR-0005 D5) before {@code compileJava}; the result compiles like hand-written contracts. */
    private static void jsc(Project project, Versions versions) {
        Configuration tool = project.getConfigurations().create("joustJsc", c -> {
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.setDescription("The .jsc lowering (julc-state-dsl)");
        });
        project.getDependencies().add(tool.getName(), "org.julclang:julc-state-dsl:" + versions.joust());
        File src = project.file(MAIN_JSC);
        Provider<Directory> out = project.getLayout().getBuildDirectory().dir("generated/sources/jsc/java/main");
        TaskProvider<JavaExec> generate = project.getTasks().register("generateJsc", JavaExec.class, t -> {
            t.setGroup("build");
            t.setDescription("Lowers " + MAIN_JSC + "/**/*.jsc to Java @StateContract sources");
            t.setClasspath(tool);
            t.getMainClass().set("org.julclang.state.dsl.JscMain");
            t.getInputs().dir(src).withPropertyName("jsc");
            t.getOutputs().dir(out).withPropertyName("java");
            t.getArgumentProviders().add(() -> List.of(src.getAbsolutePath(), out.get().getAsFile().getAbsolutePath()));
        });
        // ADR-0018 D12 (review F1): generateJsc never accepts a .jsc 2 file's ABI manifest (Name.abi.json beside it); a
        // missing or changed one fails it. Only this task accepts: it writes the manifests, to be reviewed and committed.
        // No property switches the gate off.
        Provider<Directory> scratch = project.getLayout().getBuildDirectory().dir("tmp/acceptJscAbi");
        project.getTasks().register("acceptJscAbi", JavaExec.class, t -> {
            t.setGroup("build");
            t.setDescription("Writes the ABI manifests of " + MAIN_JSC + "/**/*.jsc, accepting new and changed ones");
            t.setClasspath(tool);
            t.getMainClass().set("org.julclang.state.dsl.JscMain");
            t.getArgumentProviders().add(() -> List.of(src.getAbsolutePath(), scratch.get().getAsFile().getAbsolutePath(), "--accept-abi"));
        });
        SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        main.getJava().srcDir(project.files(out).builtBy(generate));
        // ADR-0018 D12 (review F12, S10): after compileJava, every .jsc 2 file's ABI manifest is checked against the
        // processor's description of its contract (META-INF/joust/<package>.<Contract>.json), as the npm toolchain
        // checks it at every build; part of check, so of build.
        Provider<Directory> described = project.getLayout().getBuildDirectory().dir("classes/java/main/META-INF/joust");
        TaskProvider<JavaExec> checkAbi = project.getTasks().register("checkJscAbi", JavaExec.class, t -> {
            t.setGroup("verification");
            t.setDescription("Checks the ABI manifests of " + MAIN_JSC + "/**/*.jsc against the processor's descriptions");
            t.setClasspath(tool);
            t.getMainClass().set("org.julclang.state.dsl.JscMain");
            t.dependsOn("compileJava");
            t.getInputs().dir(src).withPropertyName("jsc");
            t.getInputs().dir(described).withPropertyName("descriptions");
            t.getArgumentProviders().add(() -> List.of("--check-abi", src.getAbsolutePath(), described.get().getAsFile().getAbsolutePath()));
        });
        project.getTasks().named("check", c -> c.dependsOn(checkAbi));
    }

    /** The versions written by the build that produced this plugin. */
    record Versions(String joust, String julc, String ccl) {
        static Versions load() {
            Properties p = new Properties();
            try (InputStream in = JoustPlugin.class.getResourceAsStream("/META-INF/joust/gradle-plugin.properties")) {
                if (in == null) throw new IllegalStateException("META-INF/joust/gradle-plugin.properties is missing");
                p.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Versions(p.getProperty("joust"), p.getProperty("julc"), p.getProperty("ccl"));
        }
    }
}
