package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.List;
import static org.julclang.state.host.Wire.*;

/** D3/D4. Defer linking the serving runtime (including its CallAbort interface) until its
 * version and the launch manifest have been checked. Older runtimes can receive runtimeVersion. */
final class Handshake implements Transport.Handler {
    private final List<Path> classPath;
    private Transport.Handler session;
    Handshake(List<Path> classPath) { this.classPath = List.copyOf(classPath); }
    public long deadlineMs() { return session == null ? Transport.Handler.super.deadlineMs() : session.deadlineMs(); }
    public Transport.Reply execute(Call call, JsonNode params) throws Exception {
        if (session != null) return session.execute(call, params);
        if (!call.method.equals("initialize")) throw bad("initialize first");
        var b = fields(params.path("build"), "snapshot", "digest");
        Path root;
        try { root = Path.of(str(b, "snapshot")); }
        catch (InvalidPathException e) { throw bad("invalid snapshot path"); }
        if (!root.isAbsolute()) throw bad("snapshot must be absolute");
        var launch = Launch.scan(root, classPath);
        if (!launch.fingerprint().equals(str(b, "digest"))) throw HostError.bad("stageMismatch", "launch fingerprint differs from digest");
        Launch.runtimeVersion(getClass().getClassLoader());
        var next = new Session(launch);
        var reply = next.execute(call, params);
        return new Transport.Reply(reply.value(), () -> {
            reply.commit().run();
            session = next;
        });
    }
}
