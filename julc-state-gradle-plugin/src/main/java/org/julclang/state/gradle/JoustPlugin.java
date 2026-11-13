package org.julclang.state.gradle;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.logging.StandardOutputListener;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Property;
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
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
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
 *   <li>{@code generateJsc}, when {@code src/main/jsc} exists: {@code .jsc} lowered to Java before compiling; with it
 *       {@code acceptJscAbi}, {@code checkJscAbi}, {@code migrateJsc} and {@code remapJscDiagnostics} (ADR-0018).</li>
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

        // ADR-0018 D9 (M4b): Java (JuLC) libraries .jsc imports with java:, taken as on-chain sources only. Their jars'
        // META-INF/plutus-sources are extracted and compiled into this project (the JVM side runs the certified sources,
        // never a jar's classes), certified by generateJsc before an import is admitted (julc-state-toolchain's certifier,
        // added only when there are such libraries), checked not to reach the runtime class path from elsewhere
        // (checkJoustLibraries), and their class files recorded for the runtime's check (recordJoustLibraries).
        Configuration libraries = project.getConfigurations().create("joustLibrary", c -> {
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.setTransitive(false);
            c.setDescription("Java (JuLC) on-chain libraries .jsc imports with java: (their META-INF/plutus-sources only)");
        });
        project.getConfigurations().named(tool.getName()).configure(c -> c.getDependencies().addAllLater(project.provider(() ->
                libraries.getDependencies().isEmpty() ? List.<Dependency>of()
                        : List.of(project.getDependencies().create("org.julclang:julc-state-toolchain:" + versions.joust())))));
        Provider<Directory> librarySources = project.getLayout().getBuildDirectory().dir("joust/library-sources");
        TaskProvider<org.gradle.api.tasks.Sync> extract = project.getTasks().register("extractJoustLibraries", org.gradle.api.tasks.Sync.class, t -> {
            t.setGroup("build");
            t.setDescription("Extracts the on-chain sources (META-INF/plutus-sources) of the joustLibrary dependencies");
            t.dependsOn(libraries);
            t.from(project.provider(() -> libraries.getFiles().stream().map(project::zipTree).toList()), spec -> {
                spec.include("META-INF/plutus-sources/**/*.java");
                spec.eachFile(f -> f.setRelativePath(new org.gradle.api.file.RelativePath(true,
                        java.util.Arrays.copyOfRange(f.getRelativePath().getSegments(), 2, f.getRelativePath().getSegments().length))));
                spec.setIncludeEmptyDirs(false);
            });
            t.into(librarySources);
        });
        CommandLineJavaLibraries javaLibraries = new CommandLineJavaLibraries(libraries, librarySources);
        TaskProvider<JavaExec> generate = project.getTasks().register("generateJsc", JavaExec.class, t -> {
            t.setGroup("build");
            t.setDescription("Lowers " + MAIN_JSC + "/**/*.jsc to Java @StateContract sources");
            t.setClasspath(tool);
            t.getMainClass().set("org.julclang.state.dsl.JscMain");
            t.getInputs().dir(src).withPropertyName("jsc");
            t.getInputs().files(extract).withPropertyName("javaLibraries");
            t.getOutputs().dir(out).withPropertyName("java");
            t.getArgumentProviders().add(() -> List.of(src.getAbsolutePath(), out.get().getAsFile().getAbsolutePath()));
            t.getArgumentProviders().add(javaLibraries);
            // ADR-0018 J12: joust.lock pins the libraries from outside the sources; a mismatch fails the build.
            t.getInputs().files(project.files("joust.lock")).withPropertyName("lock");
            t.getArgumentProviders().add(() -> List.of("--lock", project.file("joust.lock").getAbsolutePath()));
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
            t.dependsOn(extract);
            t.getArgumentProviders().add(() -> List.of(src.getAbsolutePath(), scratch.get().getAsFile().getAbsolutePath(), "--accept-abi"));
            t.getArgumentProviders().add(javaLibraries);
        });
        SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        main.getJava().srcDir(project.files(out).builtBy(generate));
        main.getJava().srcDir(project.files(librarySources).builtBy(extract));
        // ADR-0018 D12 (r12, review r1 F4): each .jsc 2 contract's message map (Name.jscmap.json, beside its generated
        // Java) is a resource beside its classes, so the runtime names the .jsc sites of a refusal.
        project.getTasks().named(main.getProcessResourcesTaskName(), org.gradle.language.jvm.tasks.ProcessResources.class,
                t -> t.from(project.files(out).builtBy(generate), spec -> spec.include("**/*.jscmap.json")));
        // ADR-0018 J12: only this task writes joust.lock (to be reviewed and committed); no property switches the check off.
        Provider<Directory> lockScratch = project.getLayout().getBuildDirectory().dir("tmp/acceptJoustLock");
        project.getTasks().register("acceptJoustLock", JavaExec.class, t -> {
            t.setGroup("build");
            t.setDescription("Writes joust.lock: the SHA-256 of every library from outside the sources the build uses");
            t.setClasspath(tool);
            t.getMainClass().set("org.julclang.state.dsl.JscMain");
            t.dependsOn(extract);
            t.getArgumentProviders().add(() -> List.of(src.getAbsolutePath(), lockScratch.get().getAsFile().getAbsolutePath(), "--lock",
                    project.file("joust.lock").getAbsolutePath(), "--accept-lock"));
            t.getArgumentProviders().add(javaLibraries);
        });
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
            t.getArgumentProviders().add(javaLibraries);
        });
        project.getTasks().named("check", c -> c.dependsOn(checkAbi));
        // ADR-0018 D9 (M4b): no class compiled from a java: library's sources also reaches the runtime class path from a
        // jar or a directory (where it could shadow the certified one); and each such class file is recorded.
        TaskProvider<JavaExec> checkLibraries = project.getTasks().register("checkJoustLibraries", JavaExec.class, t -> {
            t.setGroup("verification");
            t.setDescription("Fails when a class of a java: library also reaches the runtime class path from a jar or directory");
            t.setClasspath(tool);
            t.getMainClass().set("org.julclang.state.dsl.JscMain");
            t.dependsOn(extract);
            var runtime = main.getRuntimeClasspath();
            var own = main.getOutput();
            t.getInputs().files(runtime).withPropertyName("runtimeClasspath");
            t.getArgumentProviders().add(() -> List.of("--check-library-classes", librarySources.get().getAsFile().getAbsolutePath(),
                    String.join(File.pathSeparator, runtime.minus(own).getFiles().stream().map(File::getAbsolutePath).toList())));
        });
        project.getTasks().named("check", c -> c.dependsOn(checkLibraries));
        TaskProvider<JavaExec> recordLibraries = project.getTasks().register("recordJoustLibraries", JavaExec.class, t -> {
            t.setGroup("build");
            t.setDescription("Records the SHA-256 of each class compiled from a java: library's sources (META-INF/joust/java-libraries.json)");
            t.setClasspath(tool);
            t.getMainClass().set("org.julclang.state.dsl.JscMain");
            var classes = main.getJava().getDestinationDirectory();
            t.getArgumentProviders().add(() -> List.of("--library-manifest", librarySources.get().getAsFile().getAbsolutePath(),
                    classes.get().getAsFile().getAbsolutePath()));
        });
        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME, t -> t.finalizedBy(recordLibraries));
        project.getTasks().named(main.getClassesTaskName(), t -> t.dependsOn(recordLibraries));

        // ADR-0018 D16: v1 files translated to .jsc 2, into build/jsc-migrated (or in place, with --in-place).
        Provider<Directory> migrated = project.getLayout().getBuildDirectory().dir("jsc-migrated");
        project.getTasks().register("migrateJsc", MigrateJsc.class, t -> {
            t.setGroup("build");
            t.setDescription("Translates the .jsc v1 files of " + MAIN_JSC + " to .jsc 2, into build/jsc-migrated (--in-place: replaces"
                    + " them, keeping Name.jsc.v1)");
            t.setClasspath(tool);
            t.getMainClass().set("org.julclang.state.dsl.JscMain");
            Property<Boolean> inPlace = t.getInPlace();
            t.getArgumentProviders().add(() -> inPlace.get()
                    ? List.of("--migrate", src.getAbsolutePath(), migrated.get().getAsFile().getAbsolutePath(), "--in-place")
                    : List.of("--migrate", src.getAbsolutePath(), migrated.get().getAsFile().getAbsolutePath()));
        });

        // ADR-0018 D12: javac's and the processors' diagnostics on the generated Java, reported again at their .jsc
        // positions (the source map beside the Java), as the npm toolchain reports them. compileJava's output is kept
        // in build/jsc/javac.log; when it holds anything, remapJscDiagnostics reads it (and removes it).
        Provider<RegularFile> log = project.getLayout().getBuildDirectory().file("jsc/javac.log");
        TaskProvider<JavaExec> remap = project.getTasks().register("remapJscDiagnostics", JavaExec.class, t -> {
            t.setGroup("build");
            t.setDescription("Reports compileJava's diagnostics on Java generated from .jsc at their .jsc positions");
            t.setClasspath(tool);
            t.getMainClass().set("org.julclang.state.dsl.JscMain");
            File logFile = log.get().getAsFile();
            t.onlyIf(x -> logFile.length() > 0);
            t.getArgumentProviders().add(() -> List.of("--remap", logFile.getAbsolutePath(), out.get().getAsFile().getAbsolutePath(),
                    src.getAbsolutePath()));
            t.doLast(new DeleteFile(logFile));
        });
        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile.class, t -> {
            t.doFirst(new CaptureOutput(log.get().getAsFile()));
            t.finalizedBy(remap);
        });
    }

    /** {@code --java-libraries <dir>}, when the project has joustLibrary dependencies (ADR-0018 D9, M4b). */
    static final class CommandLineJavaLibraries implements org.gradle.process.CommandLineArgumentProvider {
        private final Configuration libraries;
        private final Provider<Directory> dir;

        CommandLineJavaLibraries(Configuration libraries, Provider<Directory> dir) {
            this.libraries = libraries;
            this.dir = dir;
        }

        @Override
        public Iterable<String> asArguments() {
            return libraries.getDependencies().isEmpty() ? List.of() : List.of("--java-libraries", dir.get().getAsFile().getAbsolutePath());
        }
    }

    /** Keeps a task's standard output and error in a file (javac's diagnostics, as Gradle prints them). */
    static final class CaptureOutput implements Action<Task> {
        private final File file;

        CaptureOutput(File file) {
            this.file = file;
        }

        @Override
        public void execute(Task task) {
            try {
                Files.createDirectories(file.toPath().getParent());
                Files.writeString(file.toPath(), "");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            StandardOutputListener append = text -> {
                try {
                    Files.writeString(file.toPath(), text, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
            task.getLogging().addStandardOutputListener(append);
            task.getLogging().addStandardErrorListener(append);
        }
    }

    /** Removes a file after a task. */
    static final class DeleteFile implements Action<Task> {
        private final File file;

        DeleteFile(File file) {
            this.file = file;
        }

        @Override
        public void execute(Task task) {
            file.delete();
        }
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
