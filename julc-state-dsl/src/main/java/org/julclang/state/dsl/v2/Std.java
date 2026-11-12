package org.julclang.state.dsl.v2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code std} binding table (ADR-0018 D8), as far as M0 binds it. Each row has a {@code .jsc} signature, a Java
 * target, a conversion policy and a domain; the binding test ({@code JscV2BindingTest}) attributes a call of every
 * row's target with javac and compares the method it resolves to with {@link Binding#javaSignature()}.
 */
final class Std {
    private Std() {}

    /**
     * How {@code .jsc} values cross into the Java target (D8).
     * <ul>
     *   <li>{@code DIRECT}: every parameter and the result are the same Java type on both sides ({@code byte[]});</li>
     *   <li>{@code LONG_RESULT}: the target returns {@code long}, which goes straight into {@code BigInteger.valueOf};
     *       no {@code Int} argument is narrowed;</li>
     *   <li>{@code ADAPTER}: the target is a JOUST API adapter taking {@code BigInteger}s, which checks the row's domain
     *       with a {@code require} on both sides before it narrows and calls the stdlib.</li>
     * </ul>
     */
    enum Policy { DIRECT, LONG_RESULT, ADAPTER }

    /**
     * A binding row. {@code target} is the qualified simple call ({@code ByteStringLib.length}), whose owner class the
     * generated code imports; {@code timeTotal} says whether a time value may be an argument (total, window-independent
     * cost: D11).
     */
    record Binding(String module, String name, List<Ty> params, Ty result, String targetClass, String target,
                   Policy policy, String domain, boolean timeTotal, String javaSignature) {

        /** The Java call for the given argument expressions, with the policy's conversion of the result. */
        String call(List<String> args) {
            String c = target + "(" + String.join(", ", args) + ")";
            return policy == Policy.LONG_RESULT ? "BigInteger.valueOf(" + c + ")" : c;
        }
    }

    static final Map<String, Binding> ROWS = new LinkedHashMap<>();

    private static final String STDLIB = "org.julclang.stdlib.lib.";
    private static final String API = "org.julclang.state.api.";

    static {
        // std/crypto: hashes are total, and their cost depends on the input's length only.
        for (String h : List.of("sha2_256", "blake2b_256", "blake2b_224")) {
            add(new Binding("std/crypto", h, List.of(Ty.BYTES), Ty.BYTES, STDLIB + "CryptoLib", "CryptoLib." + h, Policy.DIRECT,
                    "any bytes", true, STDLIB + "CryptoLib." + h + "(byte[]):byte[]"));
        }
        // std/bytes: length is total; slice and byteAt go through the audited adapter, which checks their domain.
        add(new Binding("std/bytes", "length", List.of(Ty.BYTES), Ty.INT, STDLIB + "ByteStringLib", "ByteStringLib.length",
                Policy.LONG_RESULT, "any bytes; the result is in [0, 2^63)", true, STDLIB + "ByteStringLib.length(byte[]):long"));
        add(new Binding("std/bytes", "slice", List.of(Ty.BYTES, Ty.INT, Ty.INT), Ty.BYTES, API + "BytesAdapter", "BytesAdapter.slice",
                Policy.ADAPTER, "start and length in [0, 2^31), else a failure; the result is clamped to b as the builtin does",
                false, API + "BytesAdapter.slice(byte[],java.math.BigInteger,java.math.BigInteger):byte[]"));
        add(new Binding("std/bytes", "byteAt", List.of(Ty.BYTES, Ty.INT), Ty.INT, API + "BytesAdapter", "BytesAdapter.at",
                Policy.ADAPTER, "index in [0, length(b)) and below 2^31, else a failure; the result is in [0, 255]", false,
                API + "BytesAdapter.at(byte[],java.math.BigInteger):java.math.BigInteger"));
    }

    private static void add(Binding b) {
        ROWS.put(b.name(), b);
    }
}
