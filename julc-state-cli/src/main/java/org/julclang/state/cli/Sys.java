package org.julclang.state.cli;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * What the CLI needs from the machine: the working directory, the environment, the console, file ownership and
 * starting programs. Tests replace it, so every command line the CLI would run can be checked without running it.
 */
interface Sys {

    Path cwd();

    Map<String, String> env();

    PrintStream out();

    PrintStream err();

    /** The owner of {@code dir}, if the file system knows it. */
    Optional<String> owner(Path dir);

    /** This process's user name. */
    String user();

    /** Runs {@code command} in {@code dir} with {@code env} as its whole environment, the console inherited; its exit code. */
    int run(List<String> command, Path dir, Map<String, String> env);

    /** Runs {@code command} for at most {@code seconds}, capturing stdout and stderr together; empty if it can't run. */
    Optional<String> capture(List<String> command, Map<String, String> env, int seconds);

    /**
     * {@code name} resolved on {@code PATH}: the first absolute entry holding an executable regular file of that name.
     * Empty and relative entries ({@code .}, {@code bin}) are skipped, so a project directory can never supply the
     * program (ADR-0017 I3).
     */
    default Optional<Path> which(String name) {
        for (String entry : safePath(env().getOrDefault("PATH", "")).split(File.pathSeparator)) {
            if (entry.isEmpty()) continue;
            Path p = Path.of(entry).resolve(name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return Optional.of(p.toAbsolutePath());
        }
        return Optional.empty();
    }

    /** {@code PATH} without its empty and relative entries: what the CLI resolves programs with and gives its children. */
    static String safePath(String path) {
        List<String> keep = new ArrayList<>();
        for (String entry : path.split(File.pathSeparator)) {
            if (!entry.isEmpty() && Path.of(entry).isAbsolute()) keep.add(entry);
        }
        return String.join(File.pathSeparator, keep);
    }

    static Sys system() {
        return new Sys() {
            @Override
            public Path cwd() {
                return Path.of("").toAbsolutePath();
            }

            @Override
            public Map<String, String> env() {
                return System.getenv();
            }

            @Override
            public PrintStream out() {
                return System.out;
            }

            @Override
            public PrintStream err() {
                return System.err;
            }

            @Override
            public Optional<String> owner(Path dir) {
                try {
                    return Optional.of(Files.getOwner(dir).getName());
                } catch (IOException | UnsupportedOperationException | SecurityException e) {
                    return Optional.empty();
                }
            }

            @Override
            public String user() {
                return System.getProperty("user.name");
            }

            @Override
            public int run(List<String> command, Path dir, Map<String, String> env) {
                ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).inheritIO();
                pb.environment().clear();
                pb.environment().putAll(env);
                try {
                    Process p = pb.start();
                    while (true) {
                        try {
                            return p.waitFor();
                        } catch (InterruptedException e) {
                            // Ctrl-C reaches the child too (same process group): wait for it to finish.
                        }
                    }
                } catch (IOException e) {
                    err().println("joust: cannot run " + command.get(0) + ": " + e.getMessage());
                    return 127;
                }
            }

            @Override
            public Optional<String> capture(List<String> command, Map<String, String> env, int seconds) {
                ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectInput(ProcessBuilder.Redirect.from(new File(File.separatorChar == '/' ? "/dev/null" : "NUL")));
                pb.environment().clear();
                pb.environment().putAll(env);
                try {
                    Process p = pb.start();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    Thread reader = Thread.ofVirtual().start(() -> {
                        try {
                            p.getInputStream().transferTo(buf);
                        } catch (IOException ignored) {
                            // the process ended
                        }
                    });
                    if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        return Optional.empty();
                    }
                    reader.join(1000);
                    return Optional.of(buf.toString(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    return Optional.empty();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        };
    }
}
