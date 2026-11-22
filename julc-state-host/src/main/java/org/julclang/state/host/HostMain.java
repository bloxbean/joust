package org.julclang.state.host;

import java.io.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Loaded only after the bootstrap diverted fd 1 (D2). */
public final class HostMain {
    /** How long the reason line may take to reach stderr before the host halts anyway. */
    static final long REASON_MILLIS = 1000;

    public static void run(InputStream in, OutputStream out) {
        var transport = new Transport(in, out, exit(System.err, Runtime.getRuntime()::halt), new Handshake(Launch.classPath()));
        transport.run();
    }

    // A client that stopped draining stderr cannot keep the host alive: the reason line is written on its own thread
    // and the halt waits for it at most REASON_MILLIS (#149 Codex fix review).
    static Consumer<String> exit(PrintStream err, IntConsumer halt) {
        return reason -> {
            if (reason != null) {
                String line = "joust dev host: " + reason.replaceAll("[\\p{Cntrl}]", " ");
                var writer = Thread.ofPlatform().daemon().start(() -> err.println(line));
                try { writer.join(REASON_MILLIS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            halt.accept(reason == null ? 0 : 1);
        };
    }
}
