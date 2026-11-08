package org.julclang.state.toolchain.wasm;

import org.julclang.state.toolchain.Toolchain;
import org.julclang.tools.service.ServiceResult;
import org.julclang.wasm.ApiRegistry;
import org.julclang.wasm.EngineInfo;
import org.julclang.wasm.JsApi;
import org.julclang.wasm.VmApi;

import java.util.List;
import java.util.Map;

/**
 * JOUST's toolchain engine (ADR-0015 D1): a GraalVM Web Image embedding {@code julc-wasm} (JuLC ADR-064). It installs
 * {@code globalThis.joust} with JuLC's VM operations ({@code vm.*}, {@code debug.*}) and {@code joust.build}, which
 * compiles {@code .java} and {@code .jsc} contracts exactly as the Gradle build does ({@link Toolchain}).
 */
public final class JoustToolchainWasm {

    /** The engine's version; the CLI checks it against its own. */
    static final String VERSION = System.getProperty("joust.version", "dev");

    private JoustToolchainWasm() {
    }

    /** Sources by file name ({@code .java}, {@code .jsc}), and JOUST's processor options. */
    public record BuildRequest(Map<String, String> files, Map<String, String> options) {}

    public record Diagnostic(String severity, String message, String file, long line, String source) {}

    public record Contract(String name, String packageName, String validatorName, String validatorSource,
                           String scriptCborHex, String scriptHash, int scriptSizeBytes, String descriptionJson) {}

    public record BuildResult(boolean ok, List<Contract> contracts, List<Diagnostic> diagnostics) {}

    public static void main(String[] args) {
        var api = new ApiRegistry();
        new VmApi().register(api);
        api.add("joust.build", BuildRequest.class, JoustToolchainWasm::build);
        JsApi.install(api, new EngineInfo("joust", "joust-toolchain", VERSION, List.of("vm", "debug", "joust")));
    }

    static ServiceResult<BuildResult> build(BuildRequest request) {
        var r = Toolchain.build(request.files() == null ? Map.of() : request.files(),
                request.options() == null ? Map.of() : request.options());
        return ServiceResult.ok(new BuildResult(r.ok(),
                r.contracts().stream().map(c -> new Contract(c.name(), c.packageName(), c.validatorName(), c.validatorSource(),
                        c.scriptCborHex(), c.scriptHash(), c.scriptSizeBytes(), c.descriptionJson())).toList(),
                r.diagnostics().stream().map(d -> new Diagnostic(d.severity().name().toLowerCase(), d.message(), d.file(), d.line(),
                        d.source())).toList()));
    }
}
