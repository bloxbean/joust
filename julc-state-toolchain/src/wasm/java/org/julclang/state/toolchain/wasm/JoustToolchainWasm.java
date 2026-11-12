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

    public record Diagnostic(String severity, String message, String file, long line, long col, String source) {}

    public record Contract(String name, String packageName, String validatorName, String validatorSource,
                           String scriptCborHex, String scriptHash, int scriptSizeBytes, String descriptionJson,
                           String transitionsSource, Map<String, String> transitions) {}

    public record Param(String name, String type) {}

    /** A plain JuLC validator of the project (a governor, ...): see {@link Toolchain.Validator}. */
    public record Validator(String name, String packageName, String purpose, String scriptCborHex, String scriptHash,
                            int scriptSizeBytes, List<Param> params) {}

    public record BuildResult(boolean ok, List<Contract> contracts, List<Validator> validators, List<Diagnostic> diagnostics) {}

    /** A transition program (script CBOR hex) and its Data arguments (CBOR hex). */
    public record RunRequest(String program, List<String> args) {}

    /** Its result Data (CBOR hex), or the refusal: the program failed, with the contract's message in the traces. */
    public record RunResult(boolean ok, String result, String error, List<String> traces, long cpu, long mem) {}

    public static void main(String[] args) {
        var api = new ApiRegistry();
        new VmApi().register(api);
        api.add("joust.build", BuildRequest.class, JoustToolchainWasm::build);
        api.add("joust.run", RunRequest.class, JoustToolchainWasm::run);
        JsApi.install(api, new EngineInfo("joustToolchain", "joust-toolchain", VERSION, List.of("vm", "debug", "joust")));
    }

    private static final org.julclang.vm.JulcVm VM = org.julclang.vm.JulcVm.create();

    /** Runs a transition program (ADR-0015 D3) at the ledger's semantics (Plutus V3, PV11) and the ledger's per-tx budget. */
    static ServiceResult<RunResult> run(RunRequest request) {
        var hex = java.util.HexFormat.of();
        var program = org.julclang.clientlib.JulcScriptAdapter.toProgram(request.program());
        var args = new java.util.ArrayList<org.julclang.core.PlutusData>();
        for (String a : request.args()) args.add(org.julclang.core.cbor.PlutusDataCborDecoder.decode(hex.parseHex(a)));
        var r = VM.evaluateWithArgs(program, org.julclang.vm.LedgerEvaluationTarget.pv11(org.julclang.vm.PlutusLanguage.PLUTUS_V3),
                args, new org.julclang.vm.ExBudget(10_000_000_000L, 14_000_000L), org.julclang.vm.EvalOptions.DEFAULT);
        if (r instanceof org.julclang.vm.EvalResult.Success s) {
            var data = org.julclang.vm.TermExtractor.extractData(s.resultTerm());
            return ServiceResult.ok(new RunResult(true, hex.formatHex(org.julclang.core.cbor.PlutusDataCborEncoder.encode(data)), null,
                    s.traces(), s.consumed().cpuSteps(), s.consumed().memoryUnits()));
        }
        if (r instanceof org.julclang.vm.EvalResult.Failure f) {
            return ServiceResult.ok(new RunResult(false, null, f.error(), f.traces(), f.consumed().cpuSteps(), f.consumed().memoryUnits()));
        }
        var b = (org.julclang.vm.EvalResult.BudgetExhausted) r;
        return ServiceResult.ok(new RunResult(false, null, "budget exhausted", b.traces(), b.consumed().cpuSteps(), b.consumed().memoryUnits()));
    }

    static ServiceResult<BuildResult> build(BuildRequest request) {
        var r = Toolchain.build(request.files() == null ? Map.of() : request.files(),
                request.options() == null ? Map.of() : request.options());
        return ServiceResult.ok(new BuildResult(r.ok(),
                r.contracts().stream().map(c -> new Contract(c.name(), c.packageName(), c.validatorName(), c.validatorSource(),
                        c.scriptCborHex(), c.scriptHash(), c.scriptSizeBytes(), c.descriptionJson(), c.transitionsSource(),
                        c.transitions())).toList(),
                r.validators().stream().map(v -> new Validator(v.name(), v.packageName(), v.purpose(), v.scriptCborHex(),
                        v.scriptHash(), v.scriptSizeBytes(), v.params().stream().map(p -> new Param(p.name(), p.type())).toList())).toList(),
                r.diagnostics().stream().map(d -> new Diagnostic(d.severity().name().toLowerCase(), d.message(), d.file(), d.line(),
                        d.col(), d.source())).toList()));
    }
}
