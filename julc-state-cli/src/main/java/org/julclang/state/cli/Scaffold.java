package org.julclang.state.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code joust new} (ADR-0017 D3): Gradle projects from the templates in this binary, npm projects through
 * {@code create-joust}, run isolated from the working directory (I12). Nothing is written until every check passed (I9).
 */
final class Scaffold {

    static final List<String> TEMPLATES = List.of("gradle", "node", "web");
    static final List<String> LANGS = List.of("java", "jsc");
    static final String RELEASES = "https://repo.bloxbean.org/maven/releases";
    static final String SNAPSHOTS = "https://repo.bloxbean.org/maven/snapshots";
    static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile",
            "while", "true", "false", "null", "var", "yield", "record", "sealed", "permits", "non-sealed", "when",
            "module", "open", "exports", "requires", "uses", "provides", "to", "with", "opens", "transitive");
    /** A package segment Java and .jsc both accept: letters, digits and '_', not starting with a digit; no '$'. */
    static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** A local repository for CI: a file: URL with a plain path, nothing Groovy could read as code (PR #55 IS1). */
    static final Pattern REPOSITORY = Pattern.compile("file:(/|///)[A-Za-z0-9._~+-][A-Za-z0-9._~+/-]*");   // no host (IS9)
    static final Pattern EXACT_VERSION = Pattern.compile("create-joust@" + Locator.VERSION.pattern());

    /** What the user asked for, before any check. */
    record Request(String dir, String template, String lang, String pkg, String createJoust, String joustRepository) {
    }

    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    private final Sys sys;
    private final Versions versions;

    Scaffold(Sys sys, Versions versions) {
        this.sys = sys;
        this.versions = versions;
    }

    /** The defaults and every check of D3; the absolute target directory. */
    Path check(Request r) throws Refused {
        if (r.dir() == null || r.dir().isBlank()) throw new Refused("joust new needs a directory: joust new <dir>");
        if (r.dir().startsWith("-")) throw new Refused("a directory name cannot start with '-': " + r.dir());
        if (!TEMPLATES.contains(template(r))) {
            throw new Refused("unknown template '" + r.template() + "' (choose " + String.join(", ", TEMPLATES) + ")");
        }
        if (!LANGS.contains(lang(r))) throw new Refused("unknown language '" + r.lang() + "' (choose java or jsc)");
        boolean gradle = template(r).equals("gradle");
        if (!gradle && r.pkg() != null) {
            throw new Refused("-p/--package is for -t gradle only (the npm templates use the package 'app')");
        }
        if (gradle && r.createJoust() != null) throw new Refused("--create-joust is for -t node|web only");
        if (!gradle && r.joustRepository() != null) throw new Refused("--joust-repository is for -t gradle only");
        if (gradle) checkPackage(pkg(r));
        if (r.createJoust() != null) checkCreateJoust(r.createJoust());
        if (r.joustRepository() != null && !REPOSITORY.matcher(r.joustRepository()).matches()) {
            throw new Refused("--joust-repository takes a file: URL of a local directory, made of letters, digits and"
                    + " . _ ~ + - / only (for CI)");
        }
        Path target = sys.cwd().resolve(r.dir()).normalize();
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) throw new Refused(target + " exists and is not a directory");
            try (Stream<Path> s = Files.list(target)) {
                if (s.findAny().isPresent()) throw new Refused(target + " is not empty");
            } catch (IOException e) {
                throw new Refused("cannot read " + target + ": " + e.getMessage());
            }
        }
        return target;
    }

    static String template(Request r) {
        return r.template() == null ? "gradle" : r.template();
    }

    static String lang(Request r) {
        return r.lang() == null ? (template(r).equals("gradle") ? "java" : "jsc") : r.lang();
    }

    static String pkg(Request r) {
        return r.pkg() == null ? "app" : r.pkg();
    }

    static void checkPackage(String pkg) throws Refused {
        for (String seg : pkg.split("\\.", -1)) {
            if (!IDENT.matcher(seg).matches() || KEYWORDS.contains(seg) || seg.equals("_")) {   // '_' is reserved
                throw new Refused("'" + pkg + "' is not a Java package name (segments are identifiers, not keywords)");
            }
        }
    }

    static void checkCreateJoust(String spec) throws Refused {
        if (EXACT_VERSION.matcher(spec).matches()) return;
        Path p = Path.of(spec);
        if (p.isAbsolute() && spec.endsWith(".tgz") && Files.isRegularFile(p)) return;
        throw new Refused("--create-joust takes create-joust@<exact version> or an absolute path to a .tgz");
    }

    /** The project name Gradle and the README use: the directory's name, made safe. */
    static String projectName(Path target) {
        String n = target.getFileName() == null ? "joust-app" : target.getFileName().toString();
        n = n.replaceAll("[^A-Za-z0-9._-]", "-");
        return n.isEmpty() || n.startsWith(".") ? "joust-app" : n;
    }

    // ---- Gradle

    /** The files of a Gradle project, path -> content, in writing order. */
    Map<String, byte[]> gradleFiles(Request r, Path target) {
        String pkg = pkg(r);
        String path = pkg.replace('.', '/');
        boolean jsc = lang(r).equals("jsc");
        String contract = (jsc ? "src/main/jsc/" : "src/main/java/") + path + "/Counter." + (jsc ? "jsc" : "java");
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("name", groovy(projectName(target)));
        vars.put("version", versions.cli());
        vars.put("package", pkg);
        vars.put("path", path);
        vars.put("contract", contract);
        vars.put("pluginRepositories", pluginRepositories(r));
        vars.put("repositories", repositories(r));
        // ADR-0018 D14: .jsc 2, as a preview, with its ABI manifest beside it (a first build fails without one: JSC0003).
        vars.put("jscNote", jsc ? "\nThe contract is written in `.jsc` 2, a preview (ADR-0018). `Counter.abi.json` beside it records how the"
                + "\nstate and the calls are encoded on chain: the build fails when the encoding changes (JSC0002), until you accept"
                + "\nthe change with `./gradlew acceptJscAbi` and commit the new manifest. The Java the build generates from it is"
                + "\nin `build/generated/sources/jsc`.\n" : "");

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("settings.gradle", text("settings.gradle", vars));
        files.put("build.gradle", text("build.gradle", vars));
        files.put("gradle.properties", text("gradle.properties", vars));
        files.put(".gitignore", text("gitignore", vars));
        files.put("README.md", text("README.md", vars));
        files.put(contract, text(jsc ? "Counter.jsc" : "Counter.java", vars));
        if (jsc) files.put(contract.replace(".jsc", ".abi.json"), text("Counter.abi.json", vars));
        files.put("src/main/java/" + path + "/CounterMain.java", text("CounterMain.java", vars));
        files.put("src/main/java/" + path + "/Devnet.java", text("Devnet.java", vars));
        files.put("src/test/java/" + path + "/CounterTest.java", text("CounterTest.java", vars));
        files.put("gradlew", resource("templates/wrapper/gradlew"));
        files.put("gradle/wrapper/gradle-wrapper.jar", resource("templates/wrapper/gradle-wrapper.jar"));
        files.put("gradle/wrapper/gradle-wrapper.properties", resource("templates/wrapper/gradle-wrapper.properties"));
        return files;
    }

    /**
     * {@code pluginManagement}'s one source: the BloxBean repository, exclusively, for the {@code org.julclang} groups.
     * With {@code --joust-repository} (CI), that repository instead, and nothing else: this commit's plugin, no
     * fallback to a published one (ADR-0017 D3, D8).
     */
    private String pluginRepositories(Request r) {
        List<String> repos = new ArrayList<>();
        if (r.joustRepository() != null) {
            repos.add(r.joustRepository());
        } else {
            repos.add(RELEASES);
            if (versions.snapshot()) repos.add(SNAPSHOTS);
        }
        List<String> lines = new ArrayList<>();
        for (String url : repos) lines.add("                maven { url = uri('" + groovy(url) + "') }");
        return "        exclusiveContent {\n"
                + "            forRepositories(\n" + String.join(",\n", lines) + "\n            )\n"
                + "            filter {\n"
                + "                includeGroupByRegex 'org\\\\.julclang(\\\\..*)?'\n"
                + "            }\n"
                + "        }";
    }

    private String repositories(Request r) {
        StringBuilder b = new StringBuilder();
        if (r.joustRepository() != null) {
            // CI only (ADR-0017 D8): this build's JOUST modules, and nothing else, come from the staged repository.
            b.append("    exclusiveContent {\n")
                    .append("        forRepository { maven { url = uri('").append(groovy(r.joustRepository())).append("') } }\n")
                    .append("        filter {\n")
                    .append("            includeGroup 'org.julclang.joust'\n")
                    .append("            includeModuleByRegex 'org\\\\.julclang', 'julc-state-.*'\n")
                    .append("        }\n")
                    .append("    }\n");
        }
        b.append("    maven {     // BloxBean: JOUST and JuLC releases\n")
                .append("        url = uri('").append(RELEASES).append("')\n")
                .append("        mavenContent { releasesOnly() }\n")
                .append("    }");
        if (versions.snapshot()) {
            b.append("\n    maven {     // BloxBean snapshots: this JOUST is a development build\n")
                    .append("        url = uri('").append(SNAPSHOTS).append("')\n")
                    .append("        mavenContent { snapshotsOnly() }\n")
                    .append("    }");
        }
        return b.toString();
    }

    static final Pattern VAR = Pattern.compile("@@([a-zA-Z]+)@@");

    /** {@code s} inside a single-quoted Groovy string: backslashes and quotes escaped, and no line breaks. */
    static String groovy(String s) {
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) throw new IllegalArgumentException("line break in " + s);
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static byte[] text(String template, Map<String, String> vars) {
        String s = new String(resource("templates/gradle/" + template), StandardCharsets.UTF_8);
        Matcher m = VAR.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            if (v == null) throw new IllegalStateException("template " + template + ": no value for " + m.group());
            m.appendReplacement(b, Matcher.quoteReplacement(v));
        }
        m.appendTail(b);
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] resource(String name) {
        try (InputStream in = Scaffold.class.getResourceAsStream("/META-INF/joust/" + name)) {
            if (in == null) throw new IllegalStateException("missing resource META-INF/joust/" + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes a Gradle project (target checked by {@link #check}); the files written, relative to the target. */
    List<String> writeGradle(Request r, Path target) throws IOException {
        Map<String, byte[]> files = gradleFiles(r, target);
        Files.createDirectories(target);
        for (var e : files.entrySet()) {
            Path f = target.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.write(f, e.getValue());
        }
        Path gradlew = target.resolve("gradlew");
        try {
            Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {
            gradlew.toFile().setExecutable(true, false);
        }
        return new ArrayList<>(files.keySet());
    }

    // ---- npm

    /** The npm package spec create-joust runs at: this CLI's version, or {@code --create-joust}. */
    Optional<String> createJoustSpec(Request r) {
        if (r.createJoust() != null) return Optional.of(r.createJoust());
        if (versions.snapshot()) return Optional.empty();
        return Optional.of("create-joust@" + versions.cli());
    }

    /**
     * D3 step 3: npm only fetches the published tarball into the private directory (no scripts run). When {@code npm}
     * is a link to npm's JavaScript entry point (as installed by Node's packages and Homebrew), the CLI runs that file
     * with its own {@code node}, so no shebang chooses the interpreter (PR #55 IS7); otherwise it runs {@code npm}.
     * {@code --workspaces=false}: an ancestor package.json whose workspaces include tmp must not become the prefix.
     */
    static List<String> packCommand(Path node, Path npm, String spec) {
        List<String> args = List.of("pack", "--workspaces=false", "--loglevel=warn", spec);
        List<String> cmd = new ArrayList<>();
        Path js = null;
        try {
            Path real = npm.toRealPath();
            if (real.getFileName().toString().endsWith(".js")) js = real;
        } catch (IOException ignored) {
            // run npm itself
        }
        if (js != null) {
            cmd.add(node.toString());
            cmd.add(js.toString());
        } else {
            cmd.add(npm.toString());
        }
        cmd.addAll(args);
        return cmd;
    }

    /** D3 step 5: the extracted initializer, run by the {@code node} the CLI resolved (never via PATH or a shebang). */
    static List<String> createJoustCommand(Path node, Path bin, Path target, String template, String lang) {
        return List.of(node.toString(), bin.toString(), target.toString(), "--template", template, "--lang", lang);
    }

    /** The environment of every program the CLI starts: the user's, with {@code PATH} reduced to absolute entries. */
    static Map<String, String> childEnv(Map<String, String> env) {
        Map<String, String> e = new LinkedHashMap<>(env);
        e.put("PATH", Sys.safePath(env.getOrDefault("PATH", "")));
        e.remove("JOUST_DEFERRED");
        return e;
    }

    /** Runs create-joust isolated (D3): fetched by npm into a private local prefix, extracted here, run by our node. */
    int writeNpm(Request r, Path target, Path npm, Path node) throws IOException, Refused {
        String spec = createJoustSpec(r).orElseThrow(() -> new Refused("this joust (" + versions.cli()
                + ") is a development build: there is no create-joust of its version on npm; pass --create-joust"
                + " create-joust@<version> or an absolute path to a create-joust .tgz"));
        Path tmp = Files.createTempDirectory(tempBase(sys.env()), "joust-new-");
        try {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // not POSIX: the platform's temporary directory is per-user already
            }
            // Makes tmp npm's local prefix: its search for a project .npmrc stops here (F13).
            Files.writeString(tmp.resolve("package.json"), "{\"name\": \"joust-new\", \"private\": true}\n");
            Path tgz;
            if (spec.startsWith("create-joust@")) {
                int code = sys.run(packCommand(node, npm, spec), tmp, childEnv(sys.env()));
                if (code != 0) {
                    sys.err().println("joust new: npm pack " + spec + " failed (" + code + ")");
                    return code;
                }
                List<Path> tarballs;
                try (Stream<Path> files = Files.list(tmp)) {
                    tarballs = files.filter(f -> f.getFileName().toString().endsWith(".tgz")).toList();
                }
                if (tarballs.size() != 1) {
                    throw new Refused("npm pack " + spec + " wrote " + tarballs.size() + " tarballs, expected one");
                }
                tgz = tarballs.get(0);
            } else {
                tgz = Path.of(spec);
            }
            Path pkg = Tarball.extract(tgz, tmp.resolve("x")).resolve("package");
            Path bin = Tarball.bin(pkg, "create-joust");
            return sys.run(createJoustCommand(node, bin, target, template(r), lang(r)), tmp, childEnv(sys.env()));
        } catch (Tarball.Bad e) {
            throw new Refused(e.getMessage());
        } finally {
            if (sys.env().get("JOUST_KEEP_NEW_TMP") != null) {
                sys.err().println("joust new: kept " + tmp);
            } else {
                deleteTree(tmp);
            }
        }
    }

    /** Where the private directory goes: {@code $TMPDIR} if it is an absolute directory, else Java's temporary directory. */
    static Path tempBase(Map<String, String> env) {
        String t = env.get("TMPDIR");
        if (t != null && !t.isBlank() && Path.of(t).isAbsolute() && Files.isDirectory(Path.of(t))) return Path.of(t);
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort: it is in the temporary directory
                }
            });
        } catch (IOException ignored) {
            // already gone
        }
    }
}
