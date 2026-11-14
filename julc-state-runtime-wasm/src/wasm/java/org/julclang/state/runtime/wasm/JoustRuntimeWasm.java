package org.julclang.state.runtime.wasm;

import org.julclang.tools.service.ServiceResult;
import org.julclang.wasm.ApiRegistry;
import org.julclang.wasm.EngineInfo;
import org.julclang.wasm.JsApi;
import org.julclang.wasm.VmApi;

import java.util.List;

/**
 * JOUST's runtime engine (ADR-0015 D4/D5): a GraalVM Web Image embedding {@code julc-wasm} (JuLC ADR-064).
 * Installs {@code globalThis.joust} with JuLC's VM operations and {@code joust.prepare}: a call request prepared from
 * a snapshot over described contracts ({@link Prepare}). No I/O: the chain arrives in the snapshot; a missing read comes
 * back as a need.
 */
public final class JoustRuntimeWasm {

    static final String VERSION = System.getProperty("joust.version", "dev");

    private JoustRuntimeWasm() {
    }

    public static void main(String[] args) {
        var api = new ApiRegistry();
        new VmApi().register(api);
        api.add("joust.prepare", Prepare.Request.class, r -> ServiceResult.ok(Prepare.prepare(r)));
        api.add("joust.validatorScript", Scripts.Request.class, r -> ServiceResult.ok(Scripts.load(r)));
        JsApi.install(api, new EngineInfo("joustRuntime", "joust-runtime", VERSION, List.of("vm", "debug", "joust")));
    }
}
