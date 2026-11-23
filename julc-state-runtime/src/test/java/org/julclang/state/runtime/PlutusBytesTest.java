package org.julclang.state.runtime;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.CborDecodingException;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link PlutusBytes}: deep data decodes on a small stack, and for everything else it agrees with JuLC's recursive
 * {@link PlutusDataCborDecoder} (the same value, or both reject): hand-written vectors, every hex value and every datum
 * and redeemer of the recorded fixtures, and random encodings with random damage.
 */
class PlutusBytesTest {

    static final HexFormat HEX = HexFormat.of();
    static final int SMALL_STACK = 256 * 1024;

    // ---- (a) depth

    @Test
    void deepDataDecodesOnASmallStack() throws Exception {
        int n = 50_000;
        // [[[...0...]]] definite, indefinite, constructors (compact and 102), maps, and alternating kinds.
        expectDepth(repeat("81", n) + "00", n, "list");
        expectDepth(repeat("9f", n) + "00" + repeat("ff", n), n, "list");
        expectDepth(repeat("d87981", n) + "00", n, "constr");
        expectDepth(repeat("d8799f", n) + "00" + repeat("ff", n), n, "constr");
        expectDepth(repeat("d866820181", n) + "00", n, "constr");
        expectDepth(repeat("a100", n) + "00", n, "map");
        expectDepth(repeat("81d87981a100", n / 3) + "00", 3 * (n / 3), "mixed");
    }

    @Test
    void deepDataWithWhatATreeLosesDecodesOnASmallStack() throws Exception {
        // A bignum, a long chunked byte string and a repeated map key at the bottom: read from the bytes, in one pass.
        int n = 50_000;
        expectDepth(repeat("81", n) + "c24101", n, "bignum");
        expectDepth(repeat("81", n) + "5f5840" + "00".repeat(64) + "4101ff", n, "chunked bytes");
        expectDepth(repeat("81", n) + "a200000001", n + 1, "repeated key");
        expectDepth(repeat("bf00", n) + "00" + repeat("ff", n), n, "indefinite maps");
    }

    @Test
    void dataNestedInKeyPositionDecodesOnASmallStack() throws Exception {
        // {{{...{0: 0}...: 0}: 0}: 0}: each map's one key is the next map.
        int n = 50_000;
        byte[] bytes = HEX.parseHex(repeat("a1", n) + "00" + repeat("00", n));
        var result = onSmallStack(() -> PlutusBytes.decode(bytes));
        if (result.error != null) throw new AssertionError("key position", result.error);
        PlutusData d = result.value;
        int levels = 0;
        while (d instanceof PlutusData.MapData m) {
            assertEquals(1, m.entries().size());
            assertEquals(new PlutusData.IntData(0), m.entries().getFirst().value());
            d = m.entries().getFirst().key();
            levels++;
        }
        assertEquals(n, levels);
        assertEquals(new PlutusData.IntData(0), d);
    }

    @Test
    void theRecursiveDecoderOverflowsWhereTheHelperDoesNot() throws Exception {
        // What the helper replaces (documents why; if JuLC's decoder becomes iterative this is merely true of both).
        byte[] deep = HEX.parseHex(repeat("81", 50_000) + "00");
        Throwable julc = onSmallStack(() -> PlutusDataCborDecoder.decode(deep)).error;
        assertNotNull(onSmallStack(() -> PlutusBytes.decode(deep)).value);
        System.out.println("JuLC's decoder on a " + SMALL_STACK / 1024 + " KB stack: " + (julc == null ? "decoded" : julc));
    }

    private static void expectDepth(String hex, int depth, String kind) throws Exception {
        byte[] bytes = HEX.parseHex(hex);
        var result = onSmallStack(() -> PlutusBytes.decode(bytes));
        if (result.error != null) throw new AssertionError(kind + " at depth " + depth, result.error);
        // Walk down without recursion: each level holds the next as its only (or last) child.
        PlutusData d = result.value;
        int levels = 0;
        while (true) {
            List<PlutusData> children = switch (d) {
                case PlutusData.ListData l -> l.items();
                case PlutusData.ConstrData c -> c.fields();
                case PlutusData.MapData m -> m.entries().isEmpty() ? List.of() : List.of(m.entries().getLast().value());
                default -> null;
            };
            if (children == null || children.isEmpty()) break;
            levels++;
            d = children.getLast();
        }
        assertEquals(depth, levels, kind);
        // The encoder is iterative too: the value is written back on the same small stack.
        PlutusData decoded = result.value;
        var encoded = onSmallStack(() -> PlutusBytes.decode(PlutusDataCborEncoder.encode(decoded)));
        if (encoded.error != null) throw new AssertionError(kind + " re-encoded", encoded.error);
    }

    record Outcome(PlutusData value, Throwable error) {}

    private static Outcome onSmallStack(java.util.function.Supplier<PlutusData> f) throws InterruptedException {
        var out = new AtomicReference<Outcome>();
        Thread t = new Thread(null, () -> {
            try {
                out.set(new Outcome(f.get(), null));
            } catch (Throwable e) {
                out.set(new Outcome(null, e));
            }
        }, "small-stack", SMALL_STACK);
        t.start();
        t.join();
        return out.get();
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    // ---- (b) agreement with JuLC's decoder

    @Test
    void vectorsAgreeWithJulc() {
        String[] accepted = {
                "00", "17", "1817", "1900ff", "1a000000ff", "1b00000000000000ff", "1bffffffffffffffff", "20", "3bffffffffffffffff",
                "40", "4401020304", "5840" + "ab".repeat(64), "5f ff", "5f 4101 4102 ff", "5f 5840" + "01".repeat(64) + "5801ff ff",
                "c240", "c34100", "c249010000000000000000", "c35f4101ff", "c2 5f 5840" + "ff".repeat(64) + "4101 ff",
                "80", "9f ff", "83 00 01 02", "9f 00 01 ff", "98 03 00 01 02", "99 0001 00",
                "a0", "bf ff", "a2 00 01 02 03", "bf 00 01 ff", "a2 00 01 00 02", "a2 00 01 1800 02", "a2 41 01 00 5f4101ff 01",
                "a2 a2 00 00 01 01 00 a2 01 01 00 00 00", "a3 80 00 9fff 01 80 02",
                "d87980", "d8799fff", "d87a 81 00", "d87f 80", "d9050080", "d9057880", "d9 0079 80", "da 00000079 80",
                "d866 82 00 80", "d866 82 05 81 00", "d866 82 1bffffffffffffffff 80", "d866 9f 07 9f 00 ff ff",
                "d866 82 1818 80", "d9 0501 80",
                "d8799f a1 00 9f d87a80 ff 5f ff c240 ff",
        };
        String[] rejected = {
                "", "ff", "00 00", "61 61", "7f ff", "5841" + "00".repeat(65), "5f 5841" + "00".repeat(65) + " ff",
                "5f 5f ff ff", "5f 61 61 ff", "d802 41 01", "d9 0002 41 01", "c0 00", "c1 00", "c2 00", "c2 80", "c3 a0",
                "c2 c2 41 01", "d879 d879 80", "d879 00", "d879 a0", "d879 41 00", "d8 78 80", "d8 80 80", "d866 82 80 80",
                "d9057980", "d866 83 00 80 00", "d866 81 00", "d866 82 20 80", "d866 82 c240 80", "d866 82 00 d87980",
                "d866 82 00 00", "d866 00", "d81e 82 01 02", "d826 82 61 61 61 62", "f4", "f5", "f6", "f7", "f9 0000",
                "fa 00000000", "fb 0000000000000000", "81 f6", "a1 f6 00", "82 00", "a1 00", "bf 00 ff", "9f 00",
                "1c", "5c", "f8 00", "81 ff", "c6 80", "d9d9f7 00",
                // A 102 constructor's indefinite outer array must hold exactly [alternative, fields].
                "d8669fff", "d8669f00ff", "d8669f008000ff", "9fd8669f008001ffff",
        };
        for (String v : accepted) {
            byte[] b = HEX.parseHex(v.replace(" ", ""));
            var julc = PlutusDataCborDecoder.decode(b);
            assertEquals(julc, PlutusBytes.decode(b), v);
        }
        for (String v : rejected) {
            byte[] b = HEX.parseHex(v.replace(" ", ""));
            assertThrows(RuntimeException.class, () -> PlutusDataCborDecoder.decode(b), "JuLC accepts " + v);
            assertThrows(CborDecodingException.class, () -> PlutusBytes.decode(b), v);
        }
    }

    @Test
    void mapsKeepEveryPairInOrder() {
        // {0: 1, 0: 2}: a cbor-java tree keeps one; Plutus data keeps both.
        var d = (PlutusData.MapData) PlutusBytes.decode(HEX.parseHex("a200010002"));
        assertEquals(List.of(new PlutusData.Pair(new PlutusData.IntData(0), new PlutusData.IntData(1)),
                new PlutusData.Pair(new PlutusData.IntData(0), new PlutusData.IntData(2))), d.entries());
    }

    /** Every hex value in the recorded fixtures, and every datum and redeemer in their transactions. */
    @Test
    void everyFixtureValueAgreesWithJulc() throws Exception {
        List<Path> roots = Stream.of("../fixtures", "../js/state-client/test/fixtures").map(Path::of).filter(Files::isDirectory).toList();
        assumeTrue(!roots.isEmpty(), "no fixtures");
        var json = new ObjectMapper();
        Set<String> values = new LinkedHashSet<>();
        Set<String> data = new LinkedHashSet<>();   // datums and redeemers from transactions
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    collectHex(json.readTree(f.toFile()), values);
                }
            }
        }
        for (String v : values) {
            byte[] b = HEX.parseHex(v);
            if (b.length > 0 && (b[0] & 0xff) == 0x84) transactionData(b, data);
        }
        int accepted = 0;
        for (String v : values) accepted += agree(HEX.parseHex(v), v) ? 1 : 0;
        int datums = 0;
        for (String v : data) {
            assertTrue(agree(HEX.parseHex(v), v), "a datum or redeemer JuLC rejects: " + v);
            datums++;
        }
        System.out.println("fixtures: " + values.size() + " hex values (" + accepted + " Plutus data), " + datums
                + " datums and redeemers from transactions");
        assertTrue(datums > 500, "too few datums and redeemers found: " + datums);
    }

    private static void collectHex(JsonNode n, Set<String> out) {
        var stack = new ArrayList<JsonNode>();
        stack.add(n);
        while (!stack.isEmpty()) {
            JsonNode x = stack.removeLast();
            if (x.isContainerNode()) {
                x.forEach(stack::add);
            } else if (x.isTextual()) {
                String s = x.asText();
                if (!s.isEmpty() && s.length() % 2 == 0 && s.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                    out.add(s);
                }
                // Embedded JSON (chain API responses recorded as text).
                if (s.startsWith("{") || s.startsWith("[")) {
                    try {
                        stack.add(new ObjectMapper().readTree(s));
                    } catch (Exception ignored) {
                        // not JSON
                    }
                }
            }
        }
    }

    /** Inline datums of the outputs (and collateral return), witness datums and redeemers of a transaction. */
    private static void transactionData(byte[] tx, Set<String> out) {
        CborSpan t;
        try {
            t = CborSpan.of(tx);
            if (t.majorType() != 4 || t.isTagged() || t.size() != 4 || t.get(0).majorType() != 5) return;
        } catch (RuntimeException e) {
            return;
        }
        CborSpan body = t.get(0);
        for (long key : new long[] {1, 16}) {
            var outputs = body.field(key);
            if (outputs.isEmpty()) continue;
            List<CborSpan> list = key == 1 ? outputs.get().untagIf(258).items() : List.of(outputs.get());
            for (CborSpan o : list) {
                if (o.majorType() != 5) continue;
                o.field(2).ifPresent(datum -> {
                    if (datum.get(0).asLong() == 1) out.add(HEX.formatHex(datum.get(1).embedded().bytes()));
                });
            }
        }
        CborSpan witnesses = t.get(1);
        witnesses.field(4).ifPresent(ds -> ds.untagIf(258).items().forEach(d -> out.add(HEX.formatHex(d.bytes()))));
        witnesses.field(5).ifPresent(rs -> {
            if (rs.majorType() == 4) {
                rs.items().forEach(r -> out.add(HEX.formatHex(r.get(2).bytes())));
            } else {
                rs.entries().forEach(e -> out.add(HEX.formatHex(e.getValue().get(0).bytes())));
            }
        });
    }

    /** Agrees with JuLC's decoder: the same value, or both reject. @return whether it is Plutus data */
    private static boolean agree(byte[] b, String what) {
        PlutusData julc;
        try {
            julc = PlutusDataCborDecoder.decode(b);
        } catch (RuntimeException e) {
            assertThrows(CborDecodingException.class, () -> PlutusBytes.decode(b), "JuLC rejects " + what + " (" + e + ")");
            return false;
        }
        assertEquals(julc, PlutusBytes.decode(b), what);
        return true;
    }

    // ---- random encodings, with random damage

    @Test
    void randomEncodingsAgreeWithJulc() {
        var r = new Random(20261124L);
        int data = 0;
        for (int i = 0; i < 30_000; i++) {
            var out = new ByteArrayOutputStream();
            item(r, out, r.nextInt(5));
            byte[] b = out.toByteArray();
            if (r.nextInt(3) == 0) b = damage(r, b);
            data += agree(b, HEX.formatHex(b)) ? 1 : 0;
        }
        System.out.println("random: " + data + " of 30000 were Plutus data");
        assertTrue(data > 10_000, "too few valid encodings: " + data);
    }

    @Test
    void randomBytesAgreeWithJulc() {
        // Short byte strings drawn mostly from CBOR heads of every major type, most of them malformed.
        int[] heads = {0x00, 0x18, 0x19, 0x1a, 0x1b, 0x20, 0x3b, 0x40, 0x41, 0x58, 0x5f, 0x60, 0x7f, 0x80, 0x81, 0x82, 0x9f,
                0xa0, 0xa1, 0xa2, 0xbf, 0xc2, 0xc3, 0xd8, 0xd9, 0x79, 0x66, 0x05, 0x01, 0xff, 0xf4, 0xf6};
        var r = new Random(20261125L);
        int data = 0;
        for (int i = 0; i < 200_000; i++) {
            byte[] b = new byte[1 + r.nextInt(14)];
            for (int j = 0; j < b.length; j++) b[j] = (byte) (r.nextInt(3) == 0 ? r.nextInt(256) : heads[r.nextInt(heads.length)]);
            data += agree(b, HEX.formatHex(b)) ? 1 : 0;
        }
        assertTrue(data > 1_000, "too few valid encodings: " + data);
    }

    private static byte[] damage(Random r, byte[] b) {
        if (b.length == 0) return new byte[] {(byte) r.nextInt(256)};
        byte[] c;
        switch (r.nextInt(4)) {
            case 0 -> {
                c = b.clone();
                c[r.nextInt(c.length)] = (byte) r.nextInt(256);
            }
            case 1 -> c = java.util.Arrays.copyOf(b, r.nextInt(b.length));
            case 2 -> {
                c = java.util.Arrays.copyOf(b, b.length + 1);
                c[b.length] = (byte) r.nextInt(256);
            }
            default -> {
                int at = r.nextInt(b.length + 1);
                c = new byte[b.length + 1];
                System.arraycopy(b, 0, c, 0, at);
                c[at] = (byte) r.nextInt(256);
                System.arraycopy(b, at, c, at + 1, b.length - at);
            }
        }
        return c;
    }

    /** A random item, in a random encoding; mostly Plutus data, sometimes not. */
    private static void item(Random r, ByteArrayOutputStream out, int depth) {
        int kind = depth <= 0 ? r.nextInt(3) : r.nextInt(7);
        if (r.nextInt(40) == 0) kind = 7;
        switch (kind) {
            case 0 -> integer(r, out);
            case 1, 2 -> bytes(r, out, 2, r.nextInt(8) == 0 ? 60 + r.nextInt(80) : r.nextInt(40));
            case 3 -> {
                int n = r.nextInt(4);
                boolean indefinite = r.nextBoolean();
                if (indefinite) out.write(0x9f); else head(r, out, 4, n);
                for (int i = 0; i < n; i++) item(r, out, depth - 1);
                if (indefinite) out.write(0xff);
            }
            case 4, 5 -> constr(r, out, depth);
            case 6 -> map(r, out, depth);
            default -> notData(r, out);
        }
    }

    private static void integer(Random r, ByteArrayOutputStream out) {
        switch (r.nextInt(5)) {
            case 0 -> head(r, out, 0, r.nextInt(30));
            case 1 -> head(r, out, 1, r.nextInt(1000));
            case 2 -> head(r, out, r.nextInt(2), r.nextLong());
            default -> {
                int tag = r.nextBoolean() ? 2 : 3;
                if (r.nextInt(10) == 0) head(r, out, 6, tag, 1 + r.nextInt(3)); else out.write(0xc0 | tag);
                bytes(r, out, 2, r.nextInt(6) == 0 ? 65 + r.nextInt(70) : r.nextInt(20));
            }
        }
    }

    private static void bytes(Random r, ByteArrayOutputStream out, int major, int length) {
        byte[] b = new byte[length];
        r.nextBytes(b);
        if (r.nextInt(3) != 0) {
            head(r, out, major, length);
            out.writeBytes(b);
            return;
        }
        out.write(major << 5 | 31);
        int pos = 0;
        while (pos < length) {
            int chunk = Math.min(length - pos, 1 + r.nextInt(r.nextInt(10) == 0 ? 80 : 64));
            head(r, out, major, chunk);
            out.write(b, pos, chunk);
            pos += chunk;
        }
        out.write(0xff);
    }

    private static void constr(Random r, ByteArrayOutputStream out, int depth) {
        int n = r.nextInt(4);
        switch (r.nextInt(4)) {
            case 0 -> head(r, out, 6, 121 + r.nextInt(7));
            case 1 -> head(r, out, 6, 1280 + r.nextInt(121));
            case 2 -> head(r, out, 6, r.nextInt(10) == 0 ? r.nextInt(1500) : 121 + r.nextInt(7));
            default -> {
                head(r, out, 6, 102);
                boolean indefinite = r.nextInt(4) == 0;
                if (indefinite) out.write(0x9f); else head(r, out, 4, r.nextInt(12) == 0 ? 3 : 2);
                switch (r.nextInt(4)) {
                    case 0 -> head(r, out, 0, r.nextLong());
                    case 1 -> head(r, out, 0, r.nextInt(200));
                    case 2 -> integer(r, out);
                    default -> head(r, out, 0, -1L);
                }
                fields(r, out, depth, n);
                if (indefinite) out.write(0xff);
                return;
            }
        }
        fields(r, out, depth, n);
    }

    private static void fields(Random r, ByteArrayOutputStream out, int depth, int n) {
        boolean indefinite = r.nextBoolean();
        if (indefinite) out.write(0x9f); else head(r, out, 4, n);
        for (int i = 0; i < n; i++) item(r, out, depth - 1);
        if (indefinite) out.write(0xff);
    }

    private static void map(Random r, ByteArrayOutputStream out, int depth) {
        int n = r.nextInt(4);
        boolean indefinite = r.nextInt(3) == 0;
        if (indefinite) out.write(0xbf); else head(r, out, 5, n);
        var keys = new ArrayList<byte[]>();
        for (int i = 0; i < n; i++) {
            if (!keys.isEmpty() && r.nextInt(3) == 0) {
                out.writeBytes(keys.get(r.nextInt(keys.size())));   // a repeated key, byte for byte
            } else if (r.nextInt(5) == 0) {
                // A key equal in value but not in bytes, or a key whose map entries come in another order.
                int v = r.nextInt(3);
                byte[] k = r.nextBoolean() ? new byte[] {(byte) v} : new byte[] {0x18, (byte) v};
                keys.add(k);
                out.writeBytes(k);
            } else {
                var k = new ByteArrayOutputStream();
                item(r, k, depth - 1);
                keys.add(k.toByteArray());
                out.writeBytes(k.toByteArray());
            }
            item(r, out, depth - 1);
        }
        if (indefinite) out.write(0xff);
    }

    private static void notData(Random r, ByteArrayOutputStream out) {
        switch (r.nextInt(6)) {
            case 0 -> bytes(r, out, 3, r.nextInt(5));                 // a text string
            case 1 -> out.write(0xf4 + r.nextInt(4));                 // false, true, null, undefined
            case 2 -> out.writeBytes(new byte[] {(byte) 0xf9, 0, 0}); // a float
            case 3 -> { head(r, out, 6, r.nextInt(2)); integer(r, out); }
            case 4 -> { head(r, out, 6, 121); head(r, out, 5, 0); }    // a tag on a map
            default -> { head(r, out, 6, 121); head(r, out, 6, 121); head(r, out, 4, 0); }   // nested tags
        }
    }

    private static void head(Random r, ByteArrayOutputStream out, int major, long value) {
        head(r, out, major, value, r.nextInt(6) == 0 ? 1 + r.nextInt(4) : 0);
    }

    /** A head of the given width: 0 the shortest, 1-4 for 1, 2, 4 or 8 argument bytes (when the value fits). */
    private static void head(Random r, ByteArrayOutputStream out, int major, long value, int width) {
        int t = major << 5;
        int shortest = Long.compareUnsigned(value, 24) < 0 ? 0 : Long.compareUnsigned(value, 0x100) < 0 ? 1
                : Long.compareUnsigned(value, 0x10000) < 0 ? 2 : Long.compareUnsigned(value, 0x1_0000_0000L) < 0 ? 3 : 4;
        int w = Math.max(width, shortest);
        if (w == 0) {
            out.write(t | (int) value);
            return;
        }
        int size = 1 << (w - 1);
        out.write(t | (23 + w));
        for (int shift = (size - 1) * 8; shift >= 0; shift -= 8) out.write((int) (value >>> shift));
    }
}
