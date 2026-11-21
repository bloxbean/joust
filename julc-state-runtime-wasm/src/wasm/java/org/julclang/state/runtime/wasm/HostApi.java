package org.julclang.state.runtime.wasm;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;
import org.graalvm.webimage.api.JSBoolean;
import org.julclang.state.host.EngineSession;

/** The D6 callback is installed only inside a Node host worker. Ordinary prepares keep their snapshot-only ABI. */
public final class HostApi {
    private final EngineSession session = new EngineSession();
    public record Request(String frame, String setup, boolean commit) {}
    public record Result(String response) {}

    Result invoke(Request request) {
        if (request.commit()) {
            session.commit();
            return new Result(null);
        }
        String response = session.execute(request.frame(), request.setup(),
                params -> reverse(JSString.of(params)).asString(), () -> live().asBoolean(), () -> claim().asBoolean(),
                HostApi::release);
        return new Result(response);
    }

    @JS(args = {"params"}, value = "return globalThis.joustHostRead(String(params));")
    private static native JSString reverse(JSString params);

    @JS("return globalThis.joustHostLive();")
    private static native JSBoolean live();

    @JS("return globalThis.joustHostClaim();")
    private static native JSBoolean claim();

    @JS("globalThis.joustHostRelease();")
    private static native void release();
}
