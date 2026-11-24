package org.julclang.state.host;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * ADR-0022 D2 (T1, I1): fd 1 belongs to the protocol writer. {@link #divert()} is the first thing {@link DevHost#main}
 * does, before any other class of JOUST or the project is loaded, so it depends on {@code java.base} only.
 * <ul>
 *   <li><b>Linux and macOS:</b> duplicates fd 1 with {@code fcntl(1, F_DUPFD_CLOEXEC, 3)} (closed on {@code exec}, so a
 *       program native code starts never inherits the protocol pipe), keeps that descriptor for frames and writes to it
 *       with FFM {@code write} in a loop (no file is opened, I10), points fd 1 at stderr ({@code dup2(2, 1)}) so Java,
 *       native and JVM writes to fd 1 all reach stderr, then sets {@code System.setOut(System.err)}.</li>
 *   <li><b>Windows</b> (Q6, lean (a)): only {@code System.setOut(System.err)}; the protocol writer keeps the original
 *       standard output, and the client's fail-closed parsing detects any other byte there.</li>
 * </ul>
 * Needs {@code --enable-native-access=ALL-UNNAMED} (one of D2's fixed JVM options).
 */
final class Stdio {

    private Stdio() {}

    /** Moves fd 1 as above and returns the protocol's output; never null. Fails (and the host stops) if it cannot. */
    static OutputStream divert() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.startsWith("windows")) {
            OutputStream out = new FileOutputStream(FileDescriptor.out);
            System.setOut(System.err);
            return out;
        }
        int cmd;
        if (os.startsWith("mac") || os.startsWith("darwin")) cmd = 67;          // F_DUPFD_CLOEXEC, <sys/fcntl.h>
        else if (os.startsWith("linux")) cmd = 1030;                            // F_DUPFD_CLOEXEC, <fcntl.h>
        else throw new IllegalStateException("the dev host moves fd 1 on Linux and macOS only, not on " + os);
        System.out.flush();
        Linker linker = Linker.nativeLinker();
        var std = linker.defaultLookup();
        try {
            MethodHandle fcntl = linker.downcallHandle(std.find("fcntl").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    Linker.Option.firstVariadicArg(2));
            MethodHandle dup2 = linker.downcallHandle(std.find("dup2").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            int saved = (int) fcntl.invokeExact(1, cmd, 3);
            if (saved < 3) throw new IllegalStateException("fcntl(1, F_DUPFD_CLOEXEC, 3) failed: " + saved);
            int r = (int) dup2.invokeExact(2, 1);
            if (r != 1) throw new IllegalStateException("dup2(2, 1) failed: " + r);
            System.setOut(System.err);
            return new FdOut(linker, saved);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("cannot move fd 1: " + t, t);
        }
    }

    /** Writes to a saved descriptor with FFM {@code write}, in a loop (partial writes, {@code EINTR}). */
    static final class FdOut extends OutputStream {
        private static final int EINTR = 4;
        private final int fd;
        private final MethodHandle write;
        private final MemoryLayout state = Linker.Option.captureStateLayout();
        private final VarHandle errno = state.varHandle(MemoryLayout.PathElement.groupElement("errno"));

        FdOut(Linker linker, int fd) {
            this.fd = fd;
            this.write = linker.downcallHandle(linker.defaultLookup().find("write").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                    Linker.Option.captureCallState("errno"));
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) return;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(len);
                MemorySegment.copy(b, off, buf, ValueLayout.JAVA_BYTE, 0, len);
                MemorySegment st = arena.allocate(state);
                long done = 0;
                int stalls = 0;
                while (done < len) {
                    long n;
                    try {
                        n = (long) write.invokeExact(st, fd, buf.asSlice(done), (long) len - done);
                    } catch (Throwable t) {
                        throw new IOException("write failed: " + t, t);
                    }
                    if (n < 0) {
                        int e = (int) errno.get(st, 0L);
                        if (e == EINTR) continue;
                        throw new IOException("write(" + fd + ") failed: errno " + e);
                    }
                    if (n == 0 && ++stalls > 100) throw new IOException("write(" + fd + ") makes no progress");
                    done += n;
                }
            }
        }
    }
}
