package org.julclang.state.cli;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code joust}, the native JOUST command line (ADR-0017). Informational commands ({@code new}, {@code version},
 * {@code help}, any option, and any {@code --help}) are decided first and never run project code (D9, I4). Every other
 * command is a project command: in an npm project it goes to the project's own {@code @julclang/joust} (D4, I1, I2), in
 * a Gradle build to its wrapper.
 */
public final class JoustMain {

    static final String NAME = "joust";
    static final int USAGE = 2;

    /** The shared vocabulary (D9), one line per command. */
    static final Map<String, String> COMMANDS = new java.util.LinkedHashMap<>();

    static {
        COMMANDS.put("new", "new <dir> [-t gradle|node|web] [-l java|jsc] [-p <package>]   create a project");
        COMMANDS.put("build", "build       npm: compile contracts/ into build/joust/ · Gradle: ./gradlew build (and tests)");
        COMMANDS.put("test", "test        Gradle: ./gradlew test · npm: npm test");
        COMMANDS.put("run", "run         Gradle: ./gradlew run (the DevKit demo) · npm: the project's joust run");
        COMMANDS.put("deploy", "deploy      npm: publish the reference scripts to a network (--network <name>)");
        COMMANDS.put("dev", "dev         npm: attach to a network, build, deploy, rebuild on save, serve the page");
        COMMANDS.put("serve", "serve       npm: serve a built page with the chain API proxied");
        COMMANDS.put("version", "version     this joust's version, and the npm project's @julclang/joust");
        COMMANDS.put("help", "help [cmd]  this help");
    }

    private final Sys sys;
    private final Versions versions;
    private final Locator locator;

    JoustMain(Sys sys, Versions versions) {
        this.sys = sys;
        this.versions = versions;
        this.locator = new Locator(sys);
    }

    public static void main(String[] args) {
        System.exit(new JoustMain(Sys.system(), Versions.load()).run(args));
    }

    int run(String[] argv) {
        List<String> args = Arrays.asList(argv);
        if (args.isEmpty()) return help(Optional.empty());
        String cmd = args.get(0);
        if (wantsHelp(args)) {
            Optional<String> topic = cmd.startsWith("-") || cmd.equals("help") ? helpTopic(args) : Optional.of(cmd);
            int code = help(topic);
            if (topic.isPresent() && !COMMANDS.containsKey(topic.get()) && locator.locate().kind() == Locator.Kind.NPM) {
                sys.out().println();
                sys.out().println("'" + topic.get() + "' may be a command of this project's own @julclang/joust: npx joust "
                        + topic.get() + " --help");
            }
            return code;
        }
        switch (cmd) {
            case "version", "--version", "-V", "-v":
                return version();
            case "help":
                return help(helpTopic(args));
            case "new":
                return newProject(args.subList(1, args.size()));
            default:
                if (cmd.startsWith("-")) {
                    sys.err().println("joust: unknown option " + cmd + " (this is the native joust; see joust help)");
                    return USAGE;
                }
                return project(args);
        }
    }

    /** {@code --help} or {@code -h} anywhere before {@code --} (D9): {@code joust deploy --help} is help, never a deploy. */
    static boolean wantsHelp(List<String> args) {
        for (String a : args) {
            if (a.equals("--")) return false;
            if (a.equals("--help") || a.equals("-h")) return true;
        }
        return false;
    }

    private static Optional<String> helpTopic(List<String> args) {
        return args.size() > 1 && !args.get(1).startsWith("-") ? Optional.of(args.get(1)) : Optional.empty();
    }

    // ---- informational

    int help(Optional<String> topic) {
        var out = sys.out();
        if (topic.isPresent() && COMMANDS.containsKey(topic.get())) {
            out.println("joust " + COMMANDS.get(topic.get()));
            if (topic.get().equals("new")) {
                out.println();
                out.print(new CommandLine(new NewOptions()).getUsageMessage());
            }
            return 0;
        }
        out.println("joust " + versions.cli() + " (native): JOUST projects, Java or npm");
        out.println();
        out.println("Usage: joust <command> [args]");
        out.println();
        for (String line : COMMANDS.values()) out.println("  " + line);
        out.println();
        out.println("In an npm project, every command but new, version and help goes to the project's own");
        out.println("@julclang/joust (node_modules), so it does exactly what npm run would. In a Gradle build, build, test");
        out.println("and run go to its ./gradlew. https://github.com/bloxbean/joust");
        return 0;
    }

    int version() {
        sys.out().println("joust " + versions.cli() + " (native)");
        Locator.Project p = locator.locate();
        if (p.kind() == Locator.Kind.NPM) {
            Optional<Locator.Npm> npm = locator.resolveNpm(p.root());
            sys.out().println(npm.map(n -> "project: @julclang/joust " + n.version() + " (npm)")
                    .orElse("project: @julclang/joust is not installed (npm install)"));
        }
        return 0;
    }

    // ---- new

    int newProject(List<String> args) {
        NewOptions opts = new NewOptions();
        try {
            new CommandLine(opts).parseArgs(args.toArray(String[]::new));
        } catch (CommandLine.ParameterException e) {
            sys.err().println("joust new: " + e.getMessage());
            sys.err().print(new CommandLine(new NewOptions()).getUsageMessage());
            return USAGE;
        }
        Scaffold s = new Scaffold(sys, versions);
        Scaffold.Request r = opts.request();
        Path target;
        try {
            target = s.check(r);
        } catch (Scaffold.Refused e) {
            sys.err().println("joust new: " + e.getMessage());
            return USAGE;
        }
        String template = Scaffold.template(r);
        if (!template.equals("gradle")) return newNpm(s, r, target, template);
        try {
            s.writeGradle(r, target);
        } catch (IOException e) {
            sys.err().println("joust new: cannot write " + target + ": " + e.getMessage());
            return 1;
        }
        String shown = sys.cwd().relativize(target).toString();
        sys.out().println("Created " + target + " (Gradle, " + Scaffold.lang(r) + ", JOUST " + versions.cli() + ")");
        sys.out().println();
        sys.out().println("  cd " + (shown.isEmpty() ? "." : shown));
        sys.out().println("  ./gradlew build     # the validator, the client, the description; and the tests");
        sys.out().println("  ./gradlew run       # the counter on Yaci DevKit: yaci-devkit up --enable-yaci-store");
        if (Scaffold.lang(r).equals("jsc")) {
            sys.out().println();
            sys.out().println("Note: " + Scaffold.LANGUAGE_PREVIEW + ".");
        }
        javaCheck();
        return 0;
    }

    private int newNpm(Scaffold s, Scaffold.Request r, Path target, String template) {
        Optional<Path> npm = sys.which("npm");
        Optional<Path> node = sys.which("node");
        if (npm.isEmpty() || node.isEmpty()) {
            sys.err().println("joust new: -t " + template + " needs Node.js 22+ with npm on PATH (https://nodejs.org);"
                    + " or use npm create joust");
            return 1;
        }
        try {
            return s.writeNpm(r, target, npm.get(), node.get());
        } catch (Scaffold.Refused e) {
            sys.err().println("joust new: " + e.getMessage());
            return USAGE;
        } catch (IOException e) {
            sys.err().println("joust new: " + e.getMessage());
            return 1;
        }
    }

    static final Pattern JAVA_VERSION = Pattern.compile("version \"(\\d+)");

    /** D3: a Gradle project needs JDK 25 to start the wrapper and compile; say so after writing, if it is missing. */
    void javaCheck() {
        Optional<Path> java = Optional.ofNullable(sys.env().get("JAVA_HOME")).filter(h -> Path.of(h).isAbsolute())
                .map(h -> Path.of(h, "bin", "java")).filter(Files::isExecutable).or(() -> sys.which("java"));
        Optional<Integer> major = java.flatMap(j -> sys.capture(List.of(j.toString(), "-version"),
                Scaffold.childEnv(sys.env()), 10)).flatMap(s -> {
                    Matcher m = JAVA_VERSION.matcher(s);
                    return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
                });
        if (major.isEmpty() || major.get() < 25) {
            sys.out().println();
            sys.out().println("Note: this project needs JDK 25 (" + (major.map(v -> "found Java " + v)
                    .orElse("no java found on JAVA_HOME or PATH")) + "). Install one, e.g. from https://adoptium.net,"
                    + " or with SDKMAN (sdk install java 25-tem).");
        }
    }

    // ---- project commands

    int project(List<String> args) {
        Locator.Project p = locator.locate();
        return switch (p.kind()) {
            case NPM -> npm(p.root(), args);
            case GRADLE -> gradle(p.root(), args);
            case NONE -> {
                sys.err().println("joust: '" + args.get(0) + "' needs a project, and none was found from " + sys.cwd()
                        + ". Create one with joust new <dir>.");
                p.ignored().ifPresent(d -> sys.err().println(sys.user().isEmpty()
                        ? "joust: did not look in " + d + " or above: this user's uid cannot be read, so no directory"
                                + " is trusted. Add the project's directories to JOUST_SAFE_DIRS."
                        : "joust: did not look in " + d + " or above: it belongs to another user. To trust it, add it"
                                + " to JOUST_SAFE_DIRS."));
                yield USAGE;
            }
        };
    }

    /** D4.2: the project's own {@code @julclang/joust}, at the project root, with the arguments unchanged. */
    int npm(Path root, List<String> args) {
        Optional<Locator.Npm> pkg = locator.resolveNpm(root);
        if (pkg.isEmpty()) {
            sys.err().println("joust: " + root + " is an npm project; run npm install first (then joust "
                    + args.get(0) + ", or npm run <script>)");
            return 1;
        }
        Optional<Path> node = sys.which("node");
        if (node.isEmpty()) {
            sys.err().println("joust: this npm project needs Node.js (node on PATH)");
            return 1;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(node.get().toString());
        cmd.add(pkg.get().bin().toString());
        cmd.addAll(args);
        return sys.run(cmd, root, Scaffold.childEnv(sys.env()));
    }

    /** D4.3: the build's own wrapper, from the working directory (a subproject's directory selects it). */
    int gradle(Path buildDir, List<String> args) {
        String cmd = args.get(0);
        if (!List.of("build", "test", "run").contains(cmd)) {
            if (List.of("deploy", "dev", "serve").contains(cmd)) {
                sys.err().println("joust: '" + cmd + "' is a command of npm projects (the npm joust). In a Gradle"
                        + " project, deploy from Java: ./gradlew run runs the DevKit demo (CounterMain).");
            } else {
                sys.err().println("joust: in a Gradle build, the native joust runs build, test and run (./gradlew"
                        + " <task> otherwise). See joust help.");
            }
            return USAGE;
        }
        Path root = locator.gradleBuildRoot(buildDir);
        Path gradlew = root.resolve("gradlew");
        if (!Files.isRegularFile(gradlew) || !Files.isExecutable(gradlew)) {
            sys.err().println("joust: no executable Gradle wrapper at " + gradlew + " (the build at " + root
                    + " needs ./gradlew; the native joust never runs a gradle from PATH)");
            return 1;
        }
        List<String> command = new ArrayList<>();
        command.add(gradlew.toAbsolutePath().toString());
        command.addAll(args);
        return sys.run(command, sys.cwd(), Scaffold.childEnv(sys.env()));
    }
}
