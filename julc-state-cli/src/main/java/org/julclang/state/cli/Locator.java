package org.julclang.state.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which project a command is for (ADR-0017 D4). From the working directory upwards, the first directory that is an npm
 * project root ({@code joust.config.json}, or a {@code package.json} that depends on {@code @julclang/joust}) or part
 * of a Gradle build ({@code settings.gradle(.kts)}, {@code build.gradle(.kts)}) decides; npm wins in one directory.
 *
 * <p>The walk never enters a directory owned by another user (a marker planted in {@code /tmp} is ignored, as git's
 * {@code safe.directory}), unless the user lists it in {@code JOUST_SAFE_DIRS} (I5). When that rule hides a marker the
 * CLI says so, rather than silently finding no project.
 */
final class Locator {

    static final String PACKAGE = "@julclang/joust";
    static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?");

    enum Kind { NPM, GRADLE, NONE }

    /**
     * @param kind    what the nearest marker says
     * @param root    the npm project root, or the directory of the nearest Gradle build file
     * @param ignored the first directory the walk refused (another user's), if a marker sits at or above it
     */
    record Project(Kind kind, Path root, Optional<Path> ignored) {
    }

    /** An npm project's resolved {@code @julclang/joust}: the package directory (real path) and its CLI file. */
    record Npm(Path packageDir, Path bin, String version) {
    }

    private final Sys sys;
    private final Set<Path> safe = new HashSet<>();

    Locator(Sys sys) {
        this.sys = sys;
        for (String d : sys.env().getOrDefault("JOUST_SAFE_DIRS", "").split(File.pathSeparator)) {
            if (d.isBlank() || !Path.of(d).isAbsolute()) continue;
            safe.add(Path.of(d).normalize());
            try {
                safe.add(Path.of(d).toRealPath());       // /tmp/x and /private/tmp/x alike (macOS)
            } catch (IOException ignored) {
                // a listed directory that does not exist trusts nothing
            }
        }
    }

    /** Whether the walk may look at {@code dir}: the user owns it, or listed it in {@code JOUST_SAFE_DIRS}. */
    boolean trusted(Path dir) {
        if (safe.contains(dir.normalize())) return true;
        if (!safe.isEmpty()) {
            try {
                if (safe.contains(dir.toRealPath())) return true;
            } catch (IOException ignored) {
                // not resolvable: compared as written only
            }
        }
        if (sys.user().isEmpty()) return true;       // a platform without uids (not POSIX)
        return sys.owner(dir).map(o -> o.equals(sys.user())).orElse(true);
    }

    /** The walk never inspects the filesystem root itself (PR #55 IC5): a project lives in a directory below it. */
    static boolean isRoot(Path d) {
        return d.getParent() == null;
    }

    Project locate() {
        Path dir = sys.cwd().normalize();
        for (Path d = dir; d != null && !isRoot(d); d = d.getParent()) {
            if (!trusted(d)) return new Project(Kind.NONE, null, markerAtOrAbove(d) ? Optional.of(d) : Optional.empty());
            if (isNpmRoot(d)) return new Project(Kind.NPM, d, Optional.empty());
            if (isGradle(d)) return new Project(Kind.GRADLE, d, Optional.empty());
        }
        return new Project(Kind.NONE, null, Optional.empty());
    }

    /** Whether a marker file exists at or above an untrusted directory: names only, nothing is read (PR #55 IF4). */
    private static boolean markerAtOrAbove(Path d) {
        for (Path p = d; p != null && !isRoot(p); p = p.getParent()) {
            if (Files.exists(p.resolve("joust.config.json")) || Files.exists(p.resolve("package.json")) || isGradle(p)) {
                return true;
            }
        }
        return false;
    }

    static boolean isNpmRoot(Path d) {
        if (Files.isRegularFile(d.resolve("joust.config.json"))) return true;
        return Json.readSmall(d.resolve("package.json")).map(Locator::dependsOnJoust).orElse(false);
    }

    @SuppressWarnings("unchecked")
    private static boolean dependsOnJoust(Map<String, Object> pkg) {
        for (String k : List.of("dependencies", "devDependencies")) {
            if (pkg.get(k) instanceof Map<?, ?> m && m.containsKey(PACKAGE)) return true;
        }
        return false;
    }

    static boolean isGradle(Path d) {
        for (String f : List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")) {
            if (Files.isRegularFile(d.resolve(f))) return true;
        }
        return false;
    }

    static boolean hasSettings(Path d) {
        return Files.isRegularFile(d.resolve("settings.gradle")) || Files.isRegularFile(d.resolve("settings.gradle.kts"));
    }

    /** The Gradle build root above a build file's directory: the nearest trusted directory with a settings file. */
    Path gradleBuildRoot(Path buildDir) {
        for (Path d = buildDir; d != null && !isRoot(d) && trusted(d); d = d.getParent()) {
            if (hasSettings(d)) return d;
        }
        return buildDir;
    }

    /**
     * {@code @julclang/joust} as Node resolves it from the project root: the first
     * {@code node_modules/@julclang/joust/package.json} there or in a trusted ancestor, through symlinks (workspaces,
     * {@code npm link}). Its {@code bin.joust} must stay inside the package.
     */
    Optional<Npm> resolveNpm(Path root) {
        for (Path d = root; d != null && !isRoot(d) && trusted(d); d = d.getParent()) {
            Path pkgDir = d.resolve("node_modules").resolve("@julclang").resolve("joust");
            Optional<Map<String, Object>> pkg = Json.readSmall(pkgDir.resolve("package.json"));
            if (pkg.isEmpty()) continue;
            try {
                Path real = pkgDir.toRealPath();
                Object bin = pkg.get().get("bin");
                String rel = bin instanceof String s ? s : bin instanceof Map<?, ?> m && m.get("joust") instanceof String s ? s : null;
                if (rel == null) return Optional.empty();
                Path file = real.resolve(rel).normalize();
                if (!file.startsWith(real) || !Files.isRegularFile(file)) return Optional.empty();
                file = file.toRealPath();
                if (!file.startsWith(real)) return Optional.empty();   // a symlink out of the package
                Object v = pkg.get().get("version");
                String version = v instanceof String s && VERSION.matcher(s).matches() ? s : "unknown";
                return Optional.of(new Npm(real, file, version));
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
