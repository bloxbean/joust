package org.julclang.state.runtime.wasm;

import org.julclang.state.runtime.ValidatorScripts;

/**
 * The scripts of the verifiers the engine embeds, as the runtime loads them ({@link ValidatorScripts}): the image's own
 * {@code resource:} copy, accepted inside an image only (#80 review S1). {@code runtimeWasmTest} compares them with the
 * JVM build's, so the image path is covered without DevKit.
 */
public final class Scripts {
    private Scripts() {}

    /** {@code validator}: MapVerifier or OutputVerifier. */
    public record Request(String validator) {}

    /** The script's cborHex, or the refusal; and the {@code org.graalvm.nativeimage.imagecode} property seen. */
    public record Result(boolean ok, String cborHex, String error, String imagecode) {}

    public static Result load(Request r) {
        String imagecode = System.getProperty("org.graalvm.nativeimage.imagecode");
        Class<?> c = switch (r.validator()) {
            case "MapVerifier" -> org.julclang.state.verifier.MapVerifier.class;
            case "OutputVerifier" -> org.julclang.state.verifier.OutputVerifier.class;
            default -> null;
        };
        if (c == null) return new Result(false, null, "no verifier " + r.validator(), imagecode);
        try {
            return new Result(true, ValidatorScripts.cborHex(c), null, imagecode);
        } catch (RuntimeException e) {
            return new Result(false, null, e.getMessage(), imagecode);
        }
    }
}
