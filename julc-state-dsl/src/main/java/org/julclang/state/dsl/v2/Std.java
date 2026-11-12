package org.julclang.state.dsl.v2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code std} binding table (ADR-0018 D8), as far as M1a binds it: {@code std/bytes}, {@code std/crypto},
 * {@code std/math}, {@code std/bits}, {@code std/list} and {@code std/time} ({@code std/value} and
 * {@code std/address} arrive with {@code Value} and {@code Address} in M2). Each row has a {@code .jsc} signature, a
 * Java target, a conversion policy and a domain; the binding test ({@code JscV2BindingTest}) attributes a call of every
 * bound row's target with javac and compares the method it resolves to with {@link Binding#javaSignature()}.
 */
final class Std {
    private Std() {}

    /**
     * How {@code .jsc} values cross into the Java target (D8).
     * <ul>
     *   <li>{@code DIRECT}: every parameter and the result are the same Java type on both sides ({@code byte[]},
     *       {@code boolean}, {@code BigInteger}); fixed leading arguments ({@link Binding#prefix()}) are literals;</li>
     *   <li>{@code LONG_RESULT}: the target returns {@code long}, which goes straight into {@code BigInteger.valueOf};
     *       no {@code Int} argument is narrowed;</li>
     *   <li>{@code ADAPTER}: the target is a JOUST API adapter taking {@code BigInteger}s, which checks the row's domain
     *       with a {@code require} on both sides before it narrows and calls the stdlib;</li>
     *   <li>{@code GENERATED}: no library target: the lowering generates the code (typed list helpers, the validity
     *       window), and the reference interpreter implements it independently.</li>
     * </ul>
     */
    enum Policy { DIRECT, LONG_RESULT, ADAPTER, GENERATED }

    /**
     * A binding row. {@code target} is the qualified simple call ({@code ByteStringLib.length}), whose owner class the
     * generated code imports; {@code prefix} are literal leading arguments; {@code timeTotal} says whether a time value
     * may be an argument (total, window-independent cost: D11). A {@code GENERATED} row over lists is generic: its
     * {@code params} use {@code List<Int>} as a placeholder and the checker instantiates it.
     */
    record Binding(String module, String name, List<Ty> params, Ty result, String targetClass, String target, List<String> prefix,
                   Policy policy, String domain, boolean timeTotal, String javaSignature) {

        /** The Java call for the given argument expressions, with the policy's conversion of the result. */
        String call(List<String> args) {
            var all = new java.util.ArrayList<String>(prefix);
            all.addAll(args);
            String c = target + "(" + String.join(", ", all) + ")";
            return policy == Policy.LONG_RESULT ? "BigInteger.valueOf(" + c + ")" : c;
        }

        boolean generic() {
            return module.equals("std/list");
        }
    }

    static final Map<String, Binding> ROWS = new LinkedHashMap<>();

    private static final String STDLIB = "org.julclang.stdlib.lib.";
    private static final String API = "org.julclang.state.api.";
    private static final String BI = "java.math.BigInteger";
    private static final Ty INT_LIST = new Ty.ListT(Ty.INT);

    static {
        // ---- std/crypto: hashes are total, and their cost depends on the input's length only. keccak-256, ECDSA,
        //      Schnorr and RIPEMD-160 are not bound: the JVM provider lacks them (F18).
        for (String h : List.of("sha2_256", "sha3_256", "blake2b_256", "blake2b_224")) {
            add(new Binding("std/crypto", h, List.of(Ty.BYTES), Ty.BYTES, STDLIB + "CryptoLib", "CryptoLib." + h, List.of(), Policy.DIRECT,
                    "any bytes", true, STDLIB + "CryptoLib." + h + "(byte[]):byte[]"));
        }
        add(new Binding("std/crypto", "verifyEd25519", List.of(Ty.BYTES, Ty.BYTES, Ty.BYTES), Ty.BOOL, API + "CryptoAdapter",
                "CryptoAdapter.verifyEd25519", List.of(), Policy.ADAPTER,
                "key of 32 bytes and signature of 64 bytes, else a failure (where the builtin fails); then true or false",
                false, API + "CryptoAdapter.verifyEd25519(byte[],byte[],byte[]):boolean"));

        // ---- std/bytes
        add(new Binding("std/bytes", "length", List.of(Ty.BYTES), Ty.INT, STDLIB + "ByteStringLib", "ByteStringLib.length", List.of(),
                Policy.LONG_RESULT, "any bytes; the result is in [0, 2^63)", true, STDLIB + "ByteStringLib.length(byte[]):long"));
        add(new Binding("std/bytes", "concat", List.of(Ty.BYTES, Ty.BYTES), Ty.BYTES, STDLIB + "ByteStringLib", "ByteStringLib.append",
                List.of(), Policy.DIRECT, "any bytes", true, STDLIB + "ByteStringLib.append(byte[],byte[]):byte[]"));
        add(new Binding("std/bytes", "slice", List.of(Ty.BYTES, Ty.INT, Ty.INT), Ty.BYTES, API + "BytesAdapter", "BytesAdapter.slice",
                List.of(), Policy.ADAPTER, "start and length in [0, 2^31), else a failure; the result is clamped to b as the builtin does",
                false, API + "BytesAdapter.slice(byte[]," + BI + "," + BI + "):byte[]"));
        add(new Binding("std/bytes", "take", List.of(Ty.BYTES, Ty.INT), Ty.BYTES, API + "BytesAdapter", "BytesAdapter.take",
                List.of(), Policy.ADAPTER, "n in [0, 2^31), else a failure; at most length(b) bytes", false,
                API + "BytesAdapter.take(byte[]," + BI + "):byte[]"));
        add(new Binding("std/bytes", "drop", List.of(Ty.BYTES, Ty.INT), Ty.BYTES, API + "BytesAdapter", "BytesAdapter.drop",
                List.of(), Policy.ADAPTER, "n in [0, 2^31), else a failure (F18: the stdlib's drop wraps on the JVM); empty past the end",
                false, API + "BytesAdapter.drop(byte[]," + BI + "):byte[]"));
        add(new Binding("std/bytes", "byteAt", List.of(Ty.BYTES, Ty.INT), Ty.INT, API + "BytesAdapter", "BytesAdapter.at",
                List.of(), Policy.ADAPTER, "index in [0, length(b)) and below 2^31, else a failure; the result is in [0, 255]", false,
                API + "BytesAdapter.at(byte[]," + BI + "):" + BI));
        add(new Binding("std/bytes", "toInt", List.of(Ty.BYTES), Ty.INT, STDLIB + "ByteStringLib", "ByteStringLib.byteStringToInteger",
                List.of("true"), Policy.DIRECT, "any bytes, read as an unsigned big-endian integer (empty is 0)", true,
                STDLIB + "ByteStringLib.byteStringToInteger(boolean,byte[]):" + BI));
        add(new Binding("std/bytes", "toHex", List.of(Ty.BYTES), Ty.BYTES, STDLIB + "ByteStringLib", "ByteStringLib.toHex",
                List.of(), Policy.DIRECT, "any bytes; lower-case ASCII hex, two characters a byte", true,
                STDLIB + "ByteStringLib.toHex(byte[]):byte[]"));

        // ---- std/math: floor division and modulo (the operators / and % truncate, D6), powers.
        add(new Binding("std/math", "div", List.of(Ty.INT, Ty.INT), Ty.INT, API + "MathAdapter", "MathAdapter.div", List.of(),
                Policy.ADAPTER, "b != 0, else a failure; rounds toward negative infinity", false,
                API + "MathAdapter.div(" + BI + "," + BI + "):" + BI));
        add(new Binding("std/math", "mod", List.of(Ty.INT, Ty.INT), Ty.INT, API + "MathAdapter", "MathAdapter.mod", List.of(),
                Policy.ADAPTER, "b != 0, else a failure; the result has b's sign", false,
                API + "MathAdapter.mod(" + BI + "," + BI + "):" + BI));
        add(new Binding("std/math", "pow", List.of(Ty.INT, Ty.INT), Ty.INT, API + "MathAdapter", "MathAdapter.pow", List.of(),
                Policy.ADAPTER, "exponent in [0, 1024], else a failure (the cost grows with it)", false,
                API + "MathAdapter.pow(" + BI + "," + BI + "):" + BI));
        add(new Binding("std/math", "expMod", List.of(Ty.INT, Ty.INT, Ty.INT), Ty.INT, API + "MathAdapter", "MathAdapter.expMod",
                List.of(), Policy.ADAPTER, "exponent >= 0 and modulus > 0, else a failure; the result is in [0, modulus)", false,
                API + "MathAdapter.expMod(" + BI + "," + BI + "," + BI + "):" + BI));
        add(new Binding("std/math", "sign", List.of(Ty.INT), Ty.INT, STDLIB + "MathLib", "MathLib.sign", List.of(), Policy.DIRECT,
                "any Int; -1, 0 or 1", true, STDLIB + "MathLib.sign(" + BI + "):" + BI));

        // ---- std/bits
        for (String op : List.of("and", "or", "xor")) {
            add(new Binding("std/bits", op + "Bytes", List.of(Ty.BOOL, Ty.BYTES, Ty.BYTES), Ty.BYTES, STDLIB + "BitwiseLib",
                    "BitwiseLib." + op + "ByteString", List.of(), Policy.DIRECT,
                    "any bytes; with pad the result is as long as the longer, else as the shorter", true,
                    STDLIB + "BitwiseLib." + op + "ByteString(boolean,byte[],byte[]):byte[]"));
        }
        add(new Binding("std/bits", "complement", List.of(Ty.BYTES), Ty.BYTES, STDLIB + "BitwiseLib", "BitwiseLib.complementByteString",
                List.of(), Policy.DIRECT, "any bytes", true, STDLIB + "BitwiseLib.complementByteString(byte[]):byte[]"));
        add(new Binding("std/bits", "readBit", List.of(Ty.BYTES, Ty.INT), Ty.BOOL, API + "BitsAdapter", "BitsAdapter.readBit",
                List.of(), Policy.ADAPTER, "index in [0, 8 * length(b)), else a failure; bit 0 is the last byte's lowest", false,
                API + "BitsAdapter.readBit(byte[]," + BI + "):boolean"));
        add(new Binding("std/bits", "shift", List.of(Ty.BYTES, Ty.INT), Ty.BYTES, API + "BitsAdapter", "BitsAdapter.shift",
                List.of(), Policy.ADAPTER, "n in (-2^31, 2^31), else a failure; positive shifts left, zero bits fill", false,
                API + "BitsAdapter.shift(byte[]," + BI + "):byte[]"));
        add(new Binding("std/bits", "rotate", List.of(Ty.BYTES, Ty.INT), Ty.BYTES, API + "BitsAdapter", "BitsAdapter.rotate",
                List.of(), Policy.ADAPTER, "n in (-2^31, 2^31), else a failure; positive rotates left", false,
                API + "BitsAdapter.rotate(byte[]," + BI + "):byte[]"));
        add(new Binding("std/bits", "countSetBits", List.of(Ty.BYTES), Ty.INT, STDLIB + "BitwiseLib", "BitwiseLib.countSetBits",
                List.of(), Policy.LONG_RESULT, "any bytes", true, STDLIB + "BitwiseLib.countSetBits(byte[]):long"));
        add(new Binding("std/bits", "findFirstSetBit", List.of(Ty.BYTES), Ty.INT, STDLIB + "BitwiseLib", "BitwiseLib.findFirstSetBit",
                List.of(), Policy.LONG_RESULT, "any bytes; -1 when no bit is set", true, STDLIB + "BitwiseLib.findFirstSetBit(byte[]):long"));

        // ---- std/list: generic, generated helpers (typed per element type, D5), equality through D6's helpers.
        add(new Binding("std/list", "last", List.of(INT_LIST), new Ty.OptT(Ty.INT), null, null, List.of(), Policy.GENERATED,
                "any list; None when empty", true, null));
        add(new Binding("std/list", "nth", List.of(INT_LIST, Ty.INT), new Ty.OptT(Ty.INT), null, null, List.of(), Policy.GENERATED,
                "index >= 0, else a failure; None past the end", false, null));
        add(new Binding("std/list", "indexOf", List.of(INT_LIST, Ty.INT), new Ty.OptT(Ty.INT), null, null, List.of(),
                Policy.GENERATED, "any list and value (structural equality, D6); the first index, or None", true, null));

        // ---- std/time (D11): sources of time values with a documented bias, never exemptions. after and before are in
        //      the prelude and may also be imported from here.
        add(new Binding("std/time", "elapsedSince", List.of(Ty.INT), Ty.INT, null, null, List.of(), Policy.GENERATED,
                "max(0, validFrom - t): a lower bound of the time elapsed since t", true, null));
        add(new Binding("std/time", "elapsedSinceAtMost", List.of(Ty.INT), Ty.INT, null, null, List.of(), Policy.GENERATED,
                "max(0, validTo - t): an upper bound of the time elapsed since t", true, null));
        add(new Binding("std/time", "windowStart", List.of(), Ty.INT, null, null, List.of(), Policy.GENERATED,
                "validFrom: the window's start, to store", true, null));
        add(new Binding("std/time", "windowEnd", List.of(), Ty.INT, null, null, List.of(), Policy.GENERATED,
                "validTo: the window's end, to store", true, null));
    }

    /** Names a module also exports that are not rows: the prelude's time predicates, importable from std/time. */
    static final Map<String, String> ALSO_EXPORTED = Map.of("after", "std/time", "before", "std/time");

    private static void add(Binding b) {
        if (ROWS.put(b.name(), b) != null) throw new IllegalStateException("two rows named " + b.name());
    }
}
