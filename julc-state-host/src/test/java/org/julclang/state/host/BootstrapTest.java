package org.julclang.state.host;

import org.junit.jupiter.api.Test;
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
