package org.julclang.state.host;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Loaded only after the bootstrap diverted fd 1 (D2). */
public final class HostMain {
    public static void run(InputStream in, OutputStream out) {
        loadNativeIo();
        var transport = new Transport(in, out, reason -> {
            if (reason != null) System.err.println("joust dev host: " + reason.replaceAll("[\\p{Cntrl}]", " "));
            Runtime.getRuntime().halt(reason == null ? 0 : 1);
        }, new Handshake(Launch.classPath()));
        transport.run();
    }

    /**
     * I10: initialise the JDK's NIO layer before the first stdin read, so its one-time setup is not part of serving.
     * On Linux, loading {@code libnet} (which NIO's {@code IOUtil} does on first use) probes for IPv4 and IPv6 support
     * by opening and closing a {@code socket(AF_INET)} and a {@code socket(AF_INET6)}; nothing is bound or connected.
     * The host first uses NIO while it verifies the snapshot in {@code initialize}, which would put those probes inside
     * the serving interval. The CI trace (.github/scripts/trace-dev-host.sh) still refuses any socket after this point.
     */
    static void loadNativeIo() {
        try {
            Class.forName("sun.nio.ch.IOUtil", true, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            // Another JDK layout: open a file the runtime always has, read only, which initialises the same layer.
            try (var ignored = FileChannel.open(Path.of(System.getProperty("java.home"), "release"))) {
                // nothing to read
            } catch (IOException | RuntimeException absent) {
                // no such file: the trace in CI shows whether serving then opens a socket
            }
        }
    }
}
