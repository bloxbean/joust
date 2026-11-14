package org.julclang.state.toolchain;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import org.julclang.core.PlutusData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Inputs for the fuzz targets, decoded from Jazzer's bytes: Plutus data that is often almost right (the shapes a decoder
 * decides on, with lengths and quantities at the edges of its rules) and sometimes anything at all. Also each target's
 * tally, written to {@code build/fuzz/<target>.summary.txt} at the end of a run (the run's evidence: how many inputs,
 * how many reached each outcome).
 */
final class FuzzData {
    private FuzzData() {}

    static final BigInteger MAX_Q = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
    static final BigInteger MIN_Q = BigInteger.ONE.shiftLeft(127).negate();
    /** Integers at the edges of the rules a decoder applies: the signed 128-bit range, 64-bit words, small values. */
    static final List<BigInteger> EDGES = List.of(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate(), BigInteger.TWO,
            BigInteger.valueOf(255), BigInteger.valueOf(256), BigInteger.valueOf(Long.MAX_VALUE), BigInteger.valueOf(Long.MIN_VALUE),
            BigInteger.ONE.shiftLeft(64), MAX_Q, MAX_Q.add(BigInteger.ONE), MIN_Q, MIN_Q.subtract(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(200), BigInteger.ONE.shiftLeft(200).negate());
    /** Byte-string lengths at the edges of the ledger's rules: lovelace's empty policy, hashes (28), names (32), ids. */
    static final int[] LENGTHS = {0, 1, 2, 27, 28, 29, 31, 32, 33, 64, 65};

    static BigInteger integer(FuzzedDataProvider d) {
        return switch (d.consumeInt(0, 3)) {
            case 0 -> EDGES.get(d.consumeInt(0, EDGES.size() - 1));
            case 1 -> BigInteger.valueOf(d.consumeLong());
            case 2 -> BigInteger.valueOf(d.consumeInt(-3, 3));
            default -> {
                byte[] raw = d.consumeBytes(d.consumeInt(0, 40));
                yield raw.length == 0 ? BigInteger.ZERO : new BigInteger(raw);
            }
        };
    }

    static byte[] bytes(FuzzedDataProvider d) {
        int n = d.consumeBoolean() ? LENGTHS[d.consumeInt(0, LENGTHS.length - 1)] : d.consumeInt(0, 70);
        byte[] b = new byte[n];
        int fill = d.consumeInt(0, 255);
        java.util.Arrays.fill(b, (byte) fill);
        // A reserved kind's first byte (0x4F owned, 0x49 intent, 0x4B keyed), or what the input says.
        if (n > 0 && d.consumeBoolean()) b[0] = (byte) new int[]{0x4F, 0x49, 0x4B, 0x00, 0xFF}[d.consumeInt(0, 4)];
        if (n > 1 && d.consumeBoolean()) b[n - 1] = d.consumeByte();
        return b;
    }

    /** Any Plutus data, at most {@code depth} levels deep. */
    static PlutusData any(FuzzedDataProvider d, int depth) {
        int k = d.consumeInt(0, depth <= 0 ? 1 : 4);
        return switch (k) {
            case 0 -> PlutusData.integer(integer(d));
            case 1 -> PlutusData.bytes(bytes(d));
            case 2 -> {
                var fs = new ArrayList<PlutusData>();
                for (int i = d.consumeInt(0, 4); i > 0; i--) fs.add(any(d, depth - 1));
                yield new PlutusData.ConstrData(d.consumeBoolean() ? d.consumeInt(0, 3) : d.consumeInt(0, 200), fs);
            }
            case 3 -> {
                var xs = new ArrayList<PlutusData>();
                for (int i = d.consumeInt(0, 4); i > 0; i--) xs.add(any(d, depth - 1));
                yield new PlutusData.ListData(xs);
            }
            default -> {
                var ps = new ArrayList<PlutusData.Pair>();
                for (int i = d.consumeInt(0, 4); i > 0; i--) ps.add(new PlutusData.Pair(any(d, depth - 1), any(d, depth - 1)));
                yield new PlutusData.MapData(ps);
            }
        };
    }

    /**
     * A value's data, almost always shaped as one (policy bytes to a map of name bytes to quantities), with the ways a wire
     * may be wrong: policies or names out of order or repeated, a zero quantity, an empty token map, a policy or name of the
     * wrong length, a quantity out of range, or (rarely) an entry that is no value's at all.
     */
    static PlutusData valueLike(FuzzedDataProvider d) {
        var policies = new ArrayList<PlutusData.Pair>();
        int np = d.consumeInt(0, 4);
        // Mostly ascending policies (as the ledger has them), so the inner rules are reached; sometimes in the input's order.
        boolean sorted = d.consumeInt(0, 3) != 0;
        var ordered = new TreeMap<String, byte[]>();
        var raw = new ArrayList<byte[]>();
        for (int i = 0; i < np; i++) {
            byte[] p = d.consumeInt(0, 4) == 0 ? new byte[0] : (d.consumeBoolean() ? bytesOf(28, d) : bytes(d));
            raw.add(p);
            ordered.put(java.util.HexFormat.of().formatHex(p), p);
        }
        List<byte[]> ps = sorted ? new ArrayList<>(ordered.values()) : raw;
        for (byte[] p : ps) {
            if (d.consumeInt(0, 30) == 0) {
                policies.add(new PlutusData.Pair(any(d, 2), any(d, 2)));
                continue;
            }
            var tokens = new ArrayList<PlutusData.Pair>();
            for (int i = d.consumeInt(p.length == 0 ? 1 : 0, 3); i > 0; i--) {
                byte[] name = p.length == 0 ? new byte[0] : (d.consumeBoolean() ? bytesOf(d.consumeInt(0, 4), d) : bytes(d));
                BigInteger q = d.consumeInt(0, 6) == 0 ? EDGES.get(d.consumeInt(0, EDGES.size() - 1)) : BigInteger.valueOf(d.consumeInt(-2, 1_000_000));
                tokens.add(new PlutusData.Pair(PlutusData.bytes(name), PlutusData.integer(q)));
            }
            policies.add(new PlutusData.Pair(PlutusData.bytes(p), new PlutusData.MapData(tokens)));
        }
        return new PlutusData.MapData(policies);
    }

    static byte[] bytesOf(int n, FuzzedDataProvider d) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (0x70 + d.consumeInt(0, 3));
        return b;
    }

    // ------------------------------------------------------------------ #96

    /**
     * The builtin when {@code e} is #96's failure, exactly: an IllegalArgumentException thrown by one of the JVM's
     * {@code Builtins.un*Data} (top frame in org.julclang.stdlib.Builtins, message {@code unXData: expected ..., got ...})
     * because it was given data of another kind; else null. Anything else is not #96 (review r1, Codex C5).
     */
    static String builtinWrongKind(Throwable e) {
        if (!(e instanceof IllegalArgumentException) || e.getClass() != IllegalArgumentException.class) return null;
        var st = e.getStackTrace();
        if (st.length == 0 || !st[0].getClassName().equals("org.julclang.stdlib.Builtins")) return null;
        var m = java.util.regex.Pattern.compile("(un(?:B|I|Constr|List|Map)Data): expected [A-Za-z]+, got .*", java.util.regex.Pattern.DOTALL)
                .matcher(String.valueOf(e.getMessage()));
        return m.matches() && st[0].getMethodName().equals(m.group(1)) ? m.group(1) : null;
    }

    // ------------------------------------------------------------------ tallies

    /** A run's counts by outcome, written when the run ends. */
    static final class Tally {
        final String target;
        final Map<String, Long> counts = new TreeMap<>();
        long inputs;

        Tally(String target) {
            this.target = target;
        }

        synchronized void input() {
            inputs++;
        }

        synchronized void count(String outcome) {
            count(outcome, 1);
        }

        synchronized void count(String outcome, long n) {
            counts.merge(outcome, n, Long::sum);
        }

        synchronized String line() {
            return target + ": " + inputs + " inputs; " + (counts.isEmpty() ? "no outcomes" : counts.toString());
        }

        /** Prints the line and writes it to build/fuzz/<target>.summary.txt. */
        synchronized void write() {
            String line = line();
            System.out.println("[fuzz] " + line);
            try {
                Path p = Path.of("build/fuzz/" + target + ".summary.txt");
                Files.createDirectories(p.getParent());
                Files.writeString(p, line + "\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Writes a reproducer beside the run's summary (build/fuzz/<target>.failure-<n>.txt) and returns its path. */
    static Path reproducer(String target, String text) {
        try {
            Path dir = Path.of("build/fuzz");
            Files.createDirectories(dir);
            Path p = dir.resolve(target + ".failure-" + Integer.toHexString(text.hashCode()) + ".txt");
            Files.writeString(p, text);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
