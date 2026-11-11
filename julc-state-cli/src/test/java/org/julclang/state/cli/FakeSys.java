package org.julclang.state.cli;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** A {@link Sys} for tests: records what would run; optionally runs it for real (streams to files). */
final class FakeSys implements Sys {

    record Run(List<String> command, Path dir, Map<String, String> env) {
    }

    Path cwd;
    final Map<String, String> env = new HashMap<>();
    final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
    final PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
    final List<Run> runs = new ArrayList<>();
    Function<Path, Optional<String>> owners = p -> Optional.of("me");
    /** What a recorded run returns; may inspect the files present while it "runs". */
    Function<Run, Integer> onRun = r -> 0;
    /** Real execution, with stdin from and stdout/stderr to these files (I2 fidelity tests). */
    File realIn, realOut, realErr;
    String javaVersionOutput = "openjdk version \"25.0.1\" 2025-10-21";

    FakeSys(Path cwd) {
        this.cwd = cwd;
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
    }

    String outText() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    String errText() {
        return errBuf.toString(StandardCharsets.UTF_8);
    }

    @Override
    public Path cwd() {
        return cwd;
    }

    @Override
    public Map<String, String> env() {
        return env;
    }

    @Override
    public PrintStream out() {
        return out;
    }

    @Override
    public PrintStream err() {
        return err;
    }

    @Override
    public Optional<String> owner(Path dir) {
        return owners.apply(dir);
    }

    @Override
    public String user() {
        return "me";
    }

    @Override
    public int run(List<String> command, Path dir, Map<String, String> env) {
        Run r = new Run(List.copyOf(command), dir, Map.copyOf(env));
        runs.add(r);
        if (realOut == null) return onRun.apply(r);
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile())
                .redirectInput(realIn).redirectOutput(realOut).redirectError(realErr);
        pb.environment().clear();
        pb.environment().putAll(env);
        try {
            return pb.start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<String> capture(List<String> command, Map<String, String> env, int seconds) {
        return Optional.ofNullable(javaVersionOutput);
    }
}
