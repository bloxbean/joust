package org.julclang.state.host;

/**
 * The JOUST dev host (ADR-0022): {@code java <D2's options> -cp <the client's snapshot> org.julclang.state.host.DevHost
 * --stdio}. It speaks protocol version 1 (docs/protocol/dev-host-1.md) on stdin and stdout and takes everything else
 * from {@code initialize}: it reads no environment variable, no project file and no other argument (D3, D12).
 * <p>
 * This class references nothing but {@link Stdio} and {@code java.base} before fd 1 is moved (D2): the rest of the host,
 * JOUST and the project are loaded afterwards, through {@link HostMain}.
 */
public final class DevHost {

    private DevHost() {}

    public static void main(String[] args) throws Exception {
        java.io.OutputStream protocol = Stdio.divert();   // first (D2): before any other JOUST or project class loads
        if (args.length != 1 || !"--stdio".equals(args[0])) {
            System.err.println("joust dev host: the only argument is --stdio (ADR-0022 D3)");
            Runtime.getRuntime().halt(2);
        }
        // Loaded only now, by name, so nothing of it is resolved before the descriptor moved.
        Class.forName("org.julclang.state.host.HostMain")
                .getMethod("run", java.io.InputStream.class, java.io.OutputStream.class)
                .invoke(null, System.in, protocol);
    }
}
