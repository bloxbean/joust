package org.julclang.state.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.Usage;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.jvm.toolchain.JavaToolchainService;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ADR-0022 D3. Only the two tool jars are resolved separately from the application's actual graph. */
final class HostClasspath {
    private static final String GROUP = "org.julclang";
    private static final String SERVE = "julc-state-serve";
    private static final List<String> TOOLS = List.of(SERVE, "julc-state-host");

    static void register(Project project, JavaPluginExtension java, String version) {
        var host = project.getConfigurations().register("joustHost", c -> {
            c.setDescription("Dev host and serving adapter for joustHostSpec");
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.setTransitive(false);
            runtimeVariant(project, c);
            for (String name : TOOLS) {
                c.getDependencies().add(project.getDependencies().create(GROUP + ":" + name + ":" + version));
            }
        });
        project.getTasks().register("joustHostSpec", HostSpec.class, task -> {
            task.setGroup("build");
            task.setDescription("Writes the ordered dev host launch manifest to build/joust/host-spec.json");
            var main = java.getSourceSets().getByName("main");
            var application = project.files(project.provider(main::getRuntimeClasspath));
            var runtime = project.getConfigurations().getByName(main.getRuntimeClasspathConfigurationName());
            var jars = host.get();
            // Read direct runtime metadata through a separate graph. Its transitive artifacts are never
            // downloaded or launched. The non-transitive configuration itself has no dependency edges below the jars.
            var dependencies = TOOLS.stream().map(name -> project.getDependencies().create(GROUP + ":" + name + ":" + version))
                    .toArray(Dependency[]::new);
            var metadata = project.getConfigurations().detachedConfiguration(dependencies);
            runtimeVariant(project, metadata);
            task.dependsOn(application, jars);
            task.getApplication().from(application);
            task.getOptionalOutputs().from(main.getOutput());
            task.getRequiredArtifacts().from(runtime);
            task.getExtraJars().set(project.provider(() -> extraJars(runtime, application.getFiles(), jars, metadata, version)));
            var launcher = project.getExtensions().getByType(JavaToolchainService.class).launcherFor(java.getToolchain());
            task.getJavaExecutable().set(launcher.map(l -> l.getExecutablePath().getAsFile().getAbsolutePath()));
            task.getSpec().set(project.getLayout().getBuildDirectory().file("joust/host-spec.json"));
        });
    }

    private static void runtimeVariant(Project project, Configuration c) {
        c.getResolutionStrategy().getUseGlobalDependencySubstitutionRules().set(false);
        c.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
        c.getAttributes().attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.LIBRARY));
    }

    private static List<File> extraJars(Configuration runtime, Set<File> application, Configuration host,
                                        Configuration metadata, String version) {
        var selected = applicationComponents(runtime, application);
        var tools = toolJars(host, version);
        var extra = new ArrayList<File>();
        for (String name : TOOLS) {
            if (!tools.containsKey(name)) throw new GradleException("Missing joustHost jar: " + name);
            String appVersion = selected.putIfAbsent(GROUP + ":" + name, version);
            if (appVersion == null) extra.add(tools.get(name));
            else if (!version.equals(appVersion)) {
                throw new GradleException((name.equals(SERVE) ? "incompatible adapter" : "incompatible host")
                        + ": application selected " + name + ":" + appVersion + ", plugin requires " + version);
            }
        }
        checkRuntimeDependencies(metadata, selected, version);
        return extra;
    }

    private static Map<String, String> applicationComponents(Configuration runtime, Set<File> application) {
        var selected = new HashMap<String, String>();
        var omitted = new HashSet<ComponentIdentifier>();
        for (var artifact : runtime.getIncoming().getArtifacts().getArtifacts()) {
            if (!application.contains(artifact.getFile())) omitted.add(artifact.getId().getComponentIdentifier());
        }
        var graph = runtime.getIncoming().getResolutionResult();
        var root = graph.getRootComponent().get().getId();
        for (var component : graph.getAllComponents()) {
            var id = component.getModuleVersion();
            if (id != null && !root.equals(component.getId()) && !omitted.contains(component.getId())) {
                selected.put(id.getGroup() + ":" + id.getName(), id.getVersion());
            }
        }
        return selected;
    }

    private static Map<String, File> toolJars(Configuration host, String version) {
        var tools = new HashMap<String, File>();
        for (var artifact : host.getIncoming().getArtifacts().getArtifacts()) {
            if (!(artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier id)
                    || !GROUP.equals(id.getGroup()) || !TOOLS.contains(id.getModule()) || !version.equals(id.getVersion())
                    || tools.put(id.getModule(), artifact.getFile()) != null) {
                throw new GradleException("Unexpected joustHost artifact: " + artifact.getId());
            }
        }
        return tools;
    }

    private static void checkRuntimeDependencies(Configuration metadata, Map<String, String> selected, String version) {
        var root = metadata.getIncoming().getResolutionResult().getRootComponent().get();
        for (var edge : root.getDependencies()) {
            if (!(edge instanceof ResolvedDependencyResult tool)
                    || !(tool.getSelected().getId() instanceof ModuleComponentIdentifier id)
                    || !TOOLS.contains(id.getModule()) || !GROUP.equals(id.getGroup()) || !version.equals(id.getVersion())) {
                throw new GradleException("Cannot inspect joustHost runtime metadata for " + edge.getRequested());
            }
            var declarations = tool.getSelected().getDependenciesForVariant(tool.getResolvedVariant());
            // Both published modules depend on the runtime. An artifact-only repository has lost their metadata.
            if (declarations.isEmpty()) throw new GradleException("Missing runtime dependency metadata for " + id);
            for (var dep : declarations) {
                if (dep.isConstraint()) continue;
                if (!(dep.getRequested() instanceof ModuleComponentSelector module)) {
                    throw new GradleException("Unexpected dependency of " + id + ": " + dep.getRequested());
                }
                String found = selected.get(module.getGroup() + ":" + module.getModule());
                // The exact version applies to JOUST's own modules (julc-state-*); JuLC shares the group at its own
                // version (#150 review DA8).
                boolean joust = GROUP.equals(module.getGroup()) && module.getModule().startsWith("julc-state-");
                if (found == null || joust && !version.equals(found)) {
                    throw new GradleException("Unsatisfied runtime dependency of " + id + ": " + module.getDisplayName()
                            + (found == null ? " is absent" : " selected at " + found + ", plugin requires " + version));
                }
            }
        }
    }
}
