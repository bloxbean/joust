package org.julclang.state.cli;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code joust playground} in a Gradle build (ADR-0024 D3, Q1): the playground server is the npm package
 * {@code @julclang/joust} of <b>this joust's own version</b>, run from a JOUST-owned per-user cache
 * ({@code ~/.joust/playground/<version>}), never from the project. The package runs the build
 * ({@code ./gradlew build joustHostSpec}) and the JVM dev host on its verified snapshot.
 *
 * <p>The cache is installed once, by npm, into a private temporary directory beside it, then renamed into place
 * atomically (a second, concurrent install loses the rename and is discarded). npm checks every registry tarball against
 * the integrity (SHA-512) the registry publishes for that exact version and records it in the cache's
 * {@code package-lock.json}; no install script runs ({@code --ignore-scripts}). The installed version is checked against
 * this joust's. Node.js 22+ is a stated prerequisite.
 *
 * <p>For tests and CI only, {@code JOUST_PLAYGROUND_TARBALLS} names local {@code .tgz} files to install instead (a
 * development build has no package of its version on npm), and {@code JOUST_PLAYGROUND_HOME} moves the cache.
 */
final class Playground {

    static final String HOME_ENV = "JOUST_PLAYGROUND_HOME";
    static final String TARBALLS_ENV = "JOUST_PLAYGROUND_TARBALLS";
    static final int MIN_NODE = 22;
    static final Pattern NODE_VERSION = Pattern.compile("^v(\\d+)\\.");
    static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]{0,100}");
    static final String COMPLETE = ".complete";

    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    private final Sys sys;
    private final Versions versions;

    Playground(Sys sys, Versions versions) {
        this.sys = sys;
        this.versions = versions;
    }

    /** Runs the cached package's {@code joust playground <args>} in {@code project} (the Gradle project's directory). */
    int run(Path project, List<String> args) {
        try {
            Path node = node();
            Path npm = sys.which("npm").orElseThrow(() -> new Refused("joust playground needs npm on PATH (it comes with Node.js)"));
            Path bin = installed(node, npm);
            List<String> cmd = new ArrayList<>(List.of(node.toString(), bin.toString(), "playground"));
            cmd.addAll(args);
            return sys.run(cmd, project, Scaffold.childEnv(sys.env()));
        } catch (Refused e) {
            sys.err().println("joust playground: " + e.getMessage());
            return 1;
        }
    }

    /** {@code node} on PATH, version 22 or later. */
    Path node() throws Refused {
        Path node = sys.which("node").orElseThrow(() -> new Refused("the Gradle playground needs Node.js " + MIN_NODE
                + "+ (node on PATH): https://nodejs.org"));
        Optional<Integer> major = sys.capture(List.of(node.toString(), "--version"), Scaffold.childEnv(sys.env()), 10)
                .map(String::trim).flatMap(v -> {
                    Matcher m = NODE_VERSION.matcher(v);
                    return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
                });
        if (major.isEmpty()) throw new Refused("cannot tell the version of " + node + " (node --version)");
        if (major.get() < MIN_NODE) {
            throw new Refused("the Gradle playground needs Node.js " + MIN_NODE + "+; " + node + " is version " + major.get());
        }
        return node;
    }

    Path home() throws Refused {
        String h = sys.env().get(HOME_ENV);
        if (h != null && !h.isBlank()) {
            if (!Path.of(h).isAbsolute()) throw new Refused(HOME_ENV + " must be an absolute path");
            return Path.of(h);
        }
        String user = sys.env().getOrDefault("HOME", System.getProperty("user.home"));
        if (user == null || !Path.of(user).isAbsolute()) throw new Refused("no home directory for the playground's cache: set " + HOME_ENV);
        return Path.of(user, ".joust", "playground");
    }

    /** The cached package's bin/joust.js, installed first if the cache has none. */
    Path installed(Path node, Path npm) throws Refused {
        String version = versions.cli();
        if (!SAFE_VERSION.matcher(version).matches()) throw new Refused("this joust's version " + version + " is not a plain version");
        Path dir = home().resolve(version);
        if (ready(dir)) return bin(dir);
        List<String> specs = specs(version);
        try {
            Files.createDirectories(dir.getParent());
            privateDir(dir.getParent());
            Path tmp = Files.createTempDirectory(dir.getParent(), ".install-");
            try {
                privateDir(tmp);
                // tmp is npm's local prefix: a project's or an ancestor's package.json and .npmrc are never consulted.
                Files.writeString(tmp.resolve("package.json"), "{\"name\": \"joust-playground-cache\", \"private\": true}\n");
                sys.out().println("joust playground: installing @julclang/joust " + version + " into " + dir + " (once)");
                List<String> install = new ArrayList<>(List.of("install", "--workspaces=false", "--no-audit", "--no-fund",
                        "--ignore-scripts", "--save-exact", "--loglevel=warn"));
                install.addAll(specs);
                int code = sys.run(Scaffold.npmCommand(node, npm, install), tmp, Scaffold.childEnv(sys.env()));
                if (code != 0) throw new Refused("npm install " + String.join(" ", specs) + " failed (" + code + ")");
                if (!Files.isRegularFile(bin(tmp))) throw new Refused("the installed package has no bin/joust.js");
                if (sys.env().get(TARBALLS_ENV) == null) {
                    String got = installedVersion(tmp);
                    if (!version.equals(got)) throw new Refused("npm installed @julclang/joust " + got + ", not " + version);
                }
                Files.writeString(tmp.resolve(COMPLETE), version + "\n");
                try {
                    Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    throw new Refused("cannot rename the install into place atomically: " + e.getMessage());
                } catch (IOException e) {
                    // The target exists (rename(2) reports EEXIST or ENOTEMPTY): another joust playground installed it
                    // first, and that one is used. Anything else is reported.
                    if (!Files.exists(dir)) throw e;
                    if (!ready(dir)) throw new Refused(dir + " exists but is not a complete install: remove it and try again");
                }
            } finally {
                if (Files.exists(tmp)) Scaffold.deleteTree(tmp);
            }
        } catch (IOException e) {
            throw new Refused("cannot install into " + dir + ": " + e.getMessage());
        }
        if (!ready(dir)) throw new Refused("the install at " + dir + " is incomplete");
        return bin(dir);
    }

    /** What npm installs: this version from the registry, or the test/CI tarballs ({@code JOUST_PLAYGROUND_TARBALLS}). */
    List<String> specs(String version) throws Refused {
        String tarballs = sys.env().get(TARBALLS_ENV);
        if (tarballs != null && !tarballs.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String t : tarballs.split(java.io.File.pathSeparator)) {
                if (t.isBlank()) continue;
                Path p = Path.of(t);
                if (!p.isAbsolute() || !t.endsWith(".tgz") || !Files.isRegularFile(p)) {
                    throw new Refused(TARBALLS_ENV + ": " + t + " is not an absolute path to a .tgz file");
                }
                out.add(p.toString());
            }
            if (out.isEmpty()) throw new Refused(TARBALLS_ENV + " names no tarball");
            return out;
        }
        if (versions.snapshot()) {
            throw new Refused("this joust (" + versions.cli() + ") is a development build: there is no @julclang/joust of its"
                    + " version on npm. Use a released joust, or set " + TARBALLS_ENV + " to this build's npm tarballs");
        }
        return List.of("@julclang/joust@" + version);
    }

    static Path bin(Path dir) {
        return dir.resolve("node_modules").resolve("@julclang").resolve("joust").resolve("bin").resolve("joust.js");
    }

    static boolean ready(Path dir) {
        return Files.isRegularFile(dir.resolve(COMPLETE)) && Files.isRegularFile(bin(dir));
    }

    static String installedVersion(Path dir) {
        Object v = Json.readSmall(dir.resolve("node_modules/@julclang/joust/package.json")).map(m -> m.get("version")).orElse(null);
        return v instanceof String s ? s : "(none)";
    }

    private static void privateDir(Path dir) throws IOException {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // not POSIX: the user's profile directory is private already
        }
    }
}
