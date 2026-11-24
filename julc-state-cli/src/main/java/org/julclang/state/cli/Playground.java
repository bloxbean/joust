package org.julclang.state.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * <p>A cache directory is used only when it is this user's and private, and complete for exactly the install wanted
 * (cacheProblem). For tests and CI only, {@code JOUST_PLAYGROUND_TARBALLS} names local {@code .tgz} files to install
 * instead (a development build has no package of its version on npm), keyed by their bytes and only into a cache
 * {@code JOUST_PLAYGROUND_HOME} names. Install directories an interrupted install left are swept after a day.
 */
final class Playground {

    static final String HOME_ENV = "JOUST_PLAYGROUND_HOME";
    static final String TARBALLS_ENV = "JOUST_PLAYGROUND_TARBALLS";
    static final int MIN_NODE = 22;
    static final Pattern NODE_VERSION = Pattern.compile("^v(\\d+)\\.");
    static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]{0,100}");
    static final String COMPLETE = ".complete";
    static final long DAY_MS = 24L * 3600 * 1000;

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

    /**
     * The cached package's bin/joust.js, installed first if the cache has none. A cache directory is used only when it is
     * this user's and private (owned by this user, no access for group or others, not a link), its completion marker
     * names exactly the install wanted, and bin/joust.js resolves inside it: before a cache hit, and before accepting a
     * concurrent install that won the rename.
     */
    Path installed(Path node, Path npm) throws Refused {
        String version = versions.cli();
        if (!SAFE_VERSION.matcher(version).matches()) throw new Refused("this joust's version " + version + " is not a plain version");
        List<String> specs = specs(version);
        // Tarballs (tests, CI) are keyed by their bytes too: a rebuilt tarball is installed again, never ignored.
        String key = sys.env().get(TARBALLS_ENV) == null ? version : version + "-t" + digest(specs).substring(0, 16);
        Path home = home();
        Path dir = home.resolve(key);
        try {
            Files.createDirectories(home);
            // A link is refused before any chmod: setting permissions would follow it to its target.
            if (Files.isSymbolicLink(home)) throw new Refused(home + ": a link; the playground's cache must be this user's own");
            privateDir(home);
            Optional<String> homeProblem = privateProblem(home);
            if (homeProblem.isPresent()) throw new Refused(home + ": " + homeProblem.get() + "; the playground's cache must be this user's own");
            sweepInstalls(home);
            Optional<String> problem = cacheProblem(dir, key);
            if (problem.isEmpty()) return bin(dir);
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) throw new Refused(dir + ": " + problem.get() + "; remove it and run again");
            Path tmp = Files.createTempDirectory(home, ".install-");
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
                Files.writeString(tmp.resolve(COMPLETE), key + "\n");
                try {
                    Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    throw new Refused("cannot rename the install into place atomically: " + e.getMessage());
                } catch (IOException e) {
                    // The target exists (rename(2) reports EEXIST or ENOTEMPTY): another joust playground installed it
                    // first, and that one is used, once it passes the same checks. Anything else is reported.
                    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) throw e;
                }
            } finally {
                if (Files.exists(tmp, LinkOption.NOFOLLOW_LINKS)) Scaffold.deleteTree(tmp);
            }
        } catch (IOException e) {
            throw new Refused("cannot install into " + dir + ": " + e.getMessage());
        }
        Optional<String> problem = cacheProblem(dir, key);
        if (problem.isPresent()) throw new Refused(dir + ": " + problem.get() + "; remove it and run again");
        return bin(dir);
    }

    /**
     * What npm installs: this version from the registry, or the test/CI tarballs ({@code JOUST_PLAYGROUND_TARBALLS}),
     * which need their own cache ({@code JOUST_PLAYGROUND_HOME}): they never fill the real per-user cache.
     */
    List<String> specs(String version) throws Refused {
        String tarballs = sys.env().get(TARBALLS_ENV);
        if (tarballs != null) {
            String h = sys.env().get(HOME_ENV);
            if (h == null || h.isBlank()) throw new Refused(TARBALLS_ENV + " needs " + HOME_ENV + " (a cache of its own)");
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

    static String digest(List<String> tarballs) throws Refused {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String t : tarballs) {
                md.update(t.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(Path.of(t)));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new Refused("cannot read the tarballs: " + e.getMessage());
        }
    }

    static Path bin(Path dir) {
        return dir.resolve("node_modules").resolve("@julclang").resolve("joust").resolve("bin").resolve("joust.js");
    }

    /** Why {@code dir} is not this user's private directory, or empty: a link, another owner, access for others. */
    Optional<String> privateProblem(Path dir) {
        if (Files.isSymbolicLink(dir)) return Optional.of("a symbolic link");
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return Optional.of("not a directory");
        Optional<String> owner = sys.owner(dir);
        if (owner.isPresent() && !owner.get().equals(sys.user())) return Optional.of("owned by another user (uid " + owner.get() + ")");
        try {
            var perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
            for (var p : perms) {
                if (p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")) {
                    return Optional.of("accessible to others (" + PosixFilePermissions.toString(perms) + ")");
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // not POSIX: the user's profile directory is private already
        } catch (IOException e) {
            return Optional.of("unreadable (" + e.getMessage() + ")");
        }
        return Optional.empty();
    }

    /** Why the install at {@code dir} must not be used, or empty when it is a complete, private install of {@code key}. */
    Optional<String> cacheProblem(Path dir, String key) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return Optional.of("not installed");
        Optional<String> p = privateProblem(dir);
        if (p.isPresent()) return p;
        Path marker = dir.resolve(COMPLETE);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return Optional.of("an incomplete install (no " + COMPLETE + ")");
        try {
            if (Files.size(marker) > 256 || !Files.readString(marker, StandardCharsets.UTF_8).equals(key + "\n")) {
                return Optional.of("its " + COMPLETE + " names another install than " + key);
            }
            Path bin = bin(dir);
            if (!Files.isRegularFile(bin)) return Optional.of("no bin/joust.js");
            if (!bin.toRealPath().startsWith(dir.toRealPath())) return Optional.of("bin/joust.js resolves outside it");
        } catch (IOException e) {
            return Optional.of("unreadable (" + e.getMessage() + ")");
        }
        return Optional.empty();
    }

    /** Removes this user's install directories left by an interrupted install, a day old or more. */
    void sweepInstalls(Path home) {
        try (var list = Files.list(home)) {
            for (Path p : list.toList()) {
                if (!p.getFileName().toString().startsWith(".install-") || privateProblem(p).isPresent()) continue;
                if (Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis() > System.currentTimeMillis() - DAY_MS) continue;
                Scaffold.deleteTree(p);
            }
        } catch (IOException ignored) {
            // best effort
        }
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
