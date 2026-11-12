package org.julclang.state.runtime.wasm;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.runtime.described.TransitionFn;
import org.julclang.state.runtime.described.UplcTransitions;

import java.util.HexFormat;
import java.util.List;

/**
 * ADR-0020 D9: one evaluation's outcome in the comparison mode. {@code kind}: a step, the contract's refusal (with its
 * message), any other error, over the budget, or an input the strict codec rejects (abi). Two outcomes agree only
 * within the same class; messages are compared for refusals only.
 */
record Outcome(Kind kind, PlutusData step, String message, List<String> traces) {

    enum Kind { STEP, REQUIRE, ERROR, BUDGET, ABI }

    /** A program run, classified as the engine classifies it ({@link UplcTransitions}: by the require tag). */
    static Outcome of(TransitionFn.Run r) {
        if (r.step() != null) return new Outcome(Kind.STEP, r.step(), null, r.traces());
        if (r.traces().stream().anyMatch(t -> t.startsWith(UplcTransitions.REQUIRE_TAG))) {
            return new Outcome(Kind.REQUIRE, null, r.refusal(), r.traces());
        }
        if (r.refusal().endsWith(": over the execution budget")) return new Outcome(Kind.BUDGET, null, r.refusal(), r.traces());
        return new Outcome(Kind.ERROR, null, r.refusal(), r.traces());
    }

    /** The {@code joust:read} needs of this run. */
    List<String> needs() {
        return traces.stream().filter(t -> t.startsWith("joust:read ")).toList();
    }

    boolean isFinal() {
        return needs().isEmpty();
    }

    String stepHex() {
        return step == null ? null : HexFormat.of().formatHex(PlutusDataCborEncoder.encode(step));
    }

    /** What a comparison compares (D9): the step's bytes (julc-core's encoding, unsorted), or the class. */
    String comparable() {
        return switch (kind) {
            case STEP -> "step:" + stepHex();
            case REQUIRE -> "require:" + message;
            case ERROR -> "error";
            case BUDGET -> "budget";
            case ABI -> "abi";
        };
    }

    @Override
    public String toString() {
        return kind + (message == null ? "" : "(" + message + ")") + (step == null ? "" : " " + stepHex());
    }
}
