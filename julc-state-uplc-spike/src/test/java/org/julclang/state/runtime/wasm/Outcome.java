package org.julclang.state.runtime.wasm;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;

import java.util.HexFormat;
import java.util.List;

/**
 * ADR-0020 D9: the structured result of one evaluation, in the spike module (production {@code TransitionFn.Run} is
 * unchanged). {@code kind}: step, require, error, budget, abi. {@code cpu}/{@code mem}: units consumed (UPLC side).
 */
record Outcome(Kind kind, PlutusData step, String message, List<String> traces, long cpu, long mem, long nanos) {

    enum Kind { STEP, REQUIRE, ERROR, BUDGET, ABI }

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

    /** The class a comparison uses (D9): a failure without the auxiliary classification is "failure". */
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
