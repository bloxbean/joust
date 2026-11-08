package org.julclang.state.runtime.wasm;

import org.julclang.tools.service.ServiceResult;
import org.julclang.wasm.ApiRegistry;
import org.julclang.wasm.EngineInfo;
import org.julclang.wasm.JsApi;
import org.julclang.wasm.VmApi;

import java.util.List;

/**
 * JOUST's runtime engine (ADR-0015 D4/D5), gate S0: a GraalVM Web Image embedding {@code julc-wasm} (JuLC ADR-064).
 * Installs {@code globalThis.joust} with JuLC's VM operations and {@code joust.prepareS0}: a Counter call prepared
 * from a snapshot ({@link CounterPrepare}). No I/O: the chain arrives in the snapshot; a missing read comes back as a
 * need.
 */
public final class JoustRuntimeWasm {

    static final String VERSION = System.getProperty("joust.version", "dev");

    private JoustRuntimeWasm() {
    }

    public static void main(String[] args) {
        var api = new ApiRegistry();
        new VmApi().register(api);
        api.add("joust.prepareS0", CounterPrepare.Request.class, r -> ServiceResult.ok(CounterPrepare.prepare(r)));
        JsApi.install(api, new EngineInfo("joust", "joust-runtime", VERSION, List.of("vm", "debug", "joust")));
    }
}
