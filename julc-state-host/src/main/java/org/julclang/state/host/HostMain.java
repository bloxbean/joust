package org.julclang.state.host;

import java.io.*;

/** Loaded only after the bootstrap diverted fd 1 (D2). */
public final class HostMain {
    public static void run(InputStream in, OutputStream out) {
        var transport = new Transport(in, out, reason -> {
            if (reason != null) System.err.println("joust dev host: " + reason.replaceAll("[\\p{Cntrl}]", " "));
            Runtime.getRuntime().halt(reason == null ? 0 : 1);
        }, new Handshake(Launch.classPath()));
        transport.run();
    }
}
