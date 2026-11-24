package org.julclang.state.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.foreign.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.host.Wire.*;

class BootstrapTest {
    public static class Probe {
        static final PrintStream ORIGINAL = System.out;
        static class Initializer {
            static {
                System.out.println("initializer print");
                ORIGINAL.println("cached print stream");
                try (var arena = Arena.ofConfined()) {
                    var linker = Linker.nativeLinker();
                    var write = linker.downcallHandle(linker.defaultLookup().find("write").orElseThrow(), FunctionDescriptor.of(
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                    byte[] bytes = "native fd one\n".getBytes();
                    var buffer = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
                    long n = (long) write.invokeExact(1, buffer, (long) bytes.length);
                    if (n != bytes.length) throw new AssertionError();
                } catch (Throwable t) { throw new AssertionError(t); }
            }
            static void load() {}
        }
        public static void main(String[] args) throws Exception {
            System.setIn(new FilterInputStream(System.in) {
                public int read() throws IOException { Initializer.load(); return super.read(); }
            });
            DevHost.main(args);
        }
    }

    /** Exercise application-controlled code after diversion, including abrupt loss during a call. */
    public static class ActionProbe {
        public static void main(String[] args) throws Throwable {
            var protocol = Stdio.divert();
            var request = new Frames.Reader(System.in).next();
            if (request == null) throw new EOFException();
            System.out.println("action print");
            switch (args[0]) {
                case "exit" -> System.exit(41);
                case "halt" -> Runtime.getRuntime().halt(42);
                case "raw" -> {
                    new FileOutputStream(FileDescriptor.out).write("raw stdout\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    new Frames.Writer(protocol).write(request);
                }
                case "child" -> {
                    // Check CLOEXEC directly too: ProcessBuilder can close descriptors on its own.
                    var fd = protocol.getClass().getDeclaredField("fd");
                    fd.setAccessible(true);
                    var linker = Linker.nativeLinker();
                    var fcntl = linker.downcallHandle(linker.defaultLookup().find("fcntl").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                            Linker.Option.firstVariadicArg(2));
                    int flags = (int) fcntl.invokeExact(fd.getInt(protocol), 1); // F_GETFD
                    if (flags < 0 || (flags & 1) == 0) throw new AssertionError("protocol fd survives exec");
                    int status = new ProcessBuilder("/bin/sh", "-c", "echo inherited-stdout").inheritIO().start().waitFor();
                    if (status != 0) throw new AssertionError(status);
                    new Frames.Writer(protocol).write(request);
                }
                default -> throw new AssertionError();
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"exit", "halt", "child", "raw"})
    void applicationOutputAndTermination(String mode, @TempDir Path work) throws Exception {
        boolean child = mode.equals("child");
        org.junit.jupiter.api.Assumptions.assumeFalse(child && File.separatorChar == '\\');
        var log = work.resolve("stderr");
        String cp = System.getProperty("joust.host.testClassPath", System.getProperty("java.class.path"));
        var p = new ProcessBuilder(System.getProperty("joust.host.java"), "-XX:+DisplayVMOutputToStderr",
                "-Xlog:disable", "-Xlog:all=warning:stderr", "--enable-native-access=ALL-UNNAMED",
                "-cp", cp, ActionProbe.class.getName(), mode).redirectError(log.toFile()).start();
        try {
            var request = JSON.writeValueAsBytes(obj().put("jsonrpc","2.0").put("method","prepare").put("id",1));
            assertTrue(new Frames.Writer(p.getOutputStream()).write(request));
            assertTrue(p.waitFor(10, TimeUnit.SECONDS));
            var reader = new Frames.Reader(p.getInputStream());
            String stderr = Files.readString(log);
            assertTrue(stderr.contains("action print"), stderr);
            if (mode.equals("raw") && File.separatorChar == '\\') {
                assertThrows(Frames.Violation.class, reader::next);
                return;
            }
            if (child || mode.equals("raw")) {
                assertEquals(0, p.exitValue(), stderr);
                assertTrue(stderr.contains(child ? "inherited-stdout" : "raw stdout"), stderr);
                assertArrayEquals(request, reader.next());
            } else assertEquals(mode.equals("exit") ? 41 : 42, p.exitValue(), stderr);
            assertNull(reader.next(), "unexpected protocol frame or bytes");
        } finally { p.destroyForcibly(); }
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void bootstrappedFdOneIsStderrEvenForEarlierReferencesAndNativeCode(@TempDir Path work) throws Exception {
        String cp = System.getProperty("joust.host.testClassPath", System.getProperty("java.class.path"));
        var stderr = work.resolveSibling(work.getFileName() + ".stderr");
        var process = new ProcessBuilder(System.getProperty("joust.host.java"), "-XX:+DisplayVMOutputToStderr",
                "-Xlog:disable", "-Xlog:all=warning:stderr", "--enable-native-access=ALL-UNNAMED",
                "-cp", cp, Probe.class.getName(), "--stdio").directory(work.toFile()).redirectError(stderr.toFile()).start();
        try {
            var writer = new Frames.Writer(process.getOutputStream());
            assertTrue(writer.write("{\"jsonrpc\":\"2.0\",\"method\":\"describe\",\"id\":1}".getBytes()));
            var reader = new Frames.Reader(process.getInputStream());
            var response = JSON.readTree(reader.next());
            assertEquals(1, response.required("id").intValue());
            Client.error("bad", "request", response);
            assertTrue(writer.write("{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}".getBytes()));
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertNull(reader.next(), "only one response frame");
            assertEquals(0, process.exitValue());
            String diagnostics = Files.readString(stderr);
            for (String text : new String[]{"initializer print", "cached print stream", "native fd one"})
                assertTrue(diagnostics.contains(text), diagnostics);
            try (var files = Files.list(work)) { assertEquals(0, files.count()); }
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(stderr);
        }
    }
}
