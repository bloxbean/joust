package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.FourWay.*;
import static org.julclang.state.toolchain.TransitionParityTest.data;
import static org.julclang.state.toolchain.TransitionParityTest.result;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1a's constructs on J3's four legs (reference interpreter, JVM, transition program, validator): enums and
 * exhaustive {@code match} (values and statements, with pays in arms), inline {@code Map}s (canonical order, ingress
 * checks, every method), {@code BigMap}s (reads, writes, self-reads through a write, isEmpty), ranges, list take and
 * drop, and every M1a {@code std} row at its domain edges. Outcomes are also stated by hand where the rule is simple
 * enough to state independently.
 */
class JscV2M1aFourWayTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");
    static final BigInteger T0 = BigInteger.valueOf(1_800_000_000_000L);
    static final BigInteger WINDOW = BigInteger.valueOf(120_000);
    static final byte[] OWNER = fill(0x0A), ANN = fill(0xA1), BEN = fill(0xB1), CAT = fill(0x05);
    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();

    static byte[] fill(int b) {
        byte[] x = new byte[28];
        Arrays.fill(x, (byte) b);
        return x;
    }

    static BigInteger n(long v) {
        return BigInteger.valueOf(v);
    }

    static Bytes bs(String hex) {
        return Bytes.hex(hex);
    }

    static Bytes utf8(String s) {
        return Bytes.of(s.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        Map<String, String> files = new LinkedHashMap<>();
        for (String c : List.of("Ledger", "Shelf", "StdBytes", "StdMath")) {
            files.put("org/julclang/state/examples/dsl/" + c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        }
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var c : r.contracts()) {
            System.out.println("[M1a] " + c.name() + ": " + c.scriptSizeBytes() + " bytes");
            LEGS.put(c.name(), new FourWay(c.name(), files.get("org/julclang/state/examples/dsl/" + c.name() + ".jsc"), c.transitions()));
        }
    }

    static Tx at(byte[] caller, long received, long balance) {
        return tx(caller, received, balance, T0, T0.add(WINDOW));
    }

    static void report(FourWay f) {
        System.out.println("[J3 four-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator ("
                + f.refusals + " refusals, " + f.validatorRuns + " validator runs" + (f.hasMaps() ? ", at most " + f.rounds + " read rounds" : "")
                + ")");
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
    }

    static MapV map(Object... kv) {
        var m = new LinkedHashMap<Bytes, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((Bytes) kv[i], kv[i + 1]);
        return MapV.of(m);
    }

    // ------------------------------------------------------------------ Ledger: enum, match, Map, BigMap<Key, Int>

    static Rec ledger(Rec phase, MapV limits, Map<Bytes, Object> balances, long supply) {
        return rec("State", b(OWNER), phase, limits, BigMapV.of(balances), n(supply));
    }

    static final Rec OPEN = rec("Open");

    @Test
    void ledger_enumsMatchMapsAndBigMaps() throws Exception {
        var f = LEGS.get("Ledger");
        MapV limits = map(b(ANN), n(100), b(BEN), n(50));
        // init: the limits are a checked input (canonical keys of 28 bytes) and every limit is positive.
        String init = f.check("init", null, List.of(limits), at(OWNER, 0, 0), OWNER);
        assertTrue(init.startsWith("ok:"), init);
        Rec bad = ledger(OPEN, map(), Map.of(), 0);
        // Not canonical (BEN before ANN), a duplicate key, a 27-byte key, a zero limit: refused on every leg.
        var unsorted = new MapV(List.of(b(BEN), b(ANN)), List.of(n(1), n(1)));
        var duplicate = new MapV(List.of(b(ANN), b(ANN)), List.of(n(1), n(2)));
        var shortKey = new MapV(List.of(Bytes.of(new byte[27])), List.of(n(1)));
        for (MapV m : List.of(unsorted, duplicate, shortKey, map(b(ANN), n(0)))) {
            assertEquals("refused", f.check("init", null, List.of(m), at(OWNER, 0, 0), OWNER, bad), m.toString());
        }

        Map<Bytes, Object> holdings = new LinkedHashMap<>();
        holdings.put(b(ANN), n(40));
        holdings.put(b(BEN), n(10));
        List<Rec> phases = List.of(OPEN, rec("Paused", utf8("audit")), rec("Closed", n(7), b(BEN)));
        for (Rec phase : phases) {
            for (Map<Bytes, Object> bal : List.<Map<Bytes, Object>>of(Map.of(), holdings)) {
                Rec s = ledger(phase, limits, bal, bal.isEmpty() ? 0 : 50);
                f.check("pause", s, List.of(utf8("why")), null, OWNER);
                f.check("pause", s, List.of(Bytes.of(new byte[33])), null, OWNER);
                f.check("close", s, List.of(n(9)), at(OWNER, 0, 0), OWNER);
                for (long limit : List.of(0L, 1L, 75L, -1L)) {
                    for (byte[] who : List.of(ANN, CAT)) f.check("setLimit", s, List.of(b(who), n(limit)), null, OWNER);
                }
                for (byte[] to : List.of(ANN, BEN, CAT)) {
                    for (long amount : List.of(1L, 60L, 0L)) f.check("mint", s, List.of(b(to), n(amount)), null, OWNER);
                }
                for (byte[] from : List.of(ANN, CAT)) {
                    for (byte[] to : List.of(BEN, from, CAT)) {
                        for (long amount : List.of(1L, 40L, 41L)) f.check("transfer", s, List.of(b(to), n(amount)), at(from, 0, 0), from);
                    }
                }
                for (byte[] who : List.of(ANN, CAT)) {
                    f.check("balanceOf", s, List.of(b(who)), null, null);
                    f.check("limitOf", s, List.of(b(who)), null, null);
                }
                f.check("phaseCode", s, List.of(), null, null);
                f.check("settle", s, List.of(), at(OWNER, 0, 10_000_001), OWNER);
            }
        }
        // By hand: a self-transfer keeps the balance; a transfer to a new holder moves it; setLimit keeps keys canonical.
        Rec s = ledger(OPEN, limits, holdings, 50);
        String self = f.check("transfer", s, List.of(b(ANN), n(40)), at(ANN, 0, 0), ANN);
        assertEquals("ok:" + result(f.java(ledger(OPEN, limits, holdings, 50)), null), self, "a self-transfer changes nothing");
        Map<Bytes, Object> moved = new LinkedHashMap<>(holdings);
        moved.put(b(ANN), n(15));
        moved.put(b(CAT), n(25));
        assertEquals("ok:" + result(f.java(ledger(OPEN, limits, moved, 50)), null),
                f.check("transfer", s, List.of(b(CAT), n(25)), at(ANN, 0, 0), ANN));
        Rec lowered = f.next("setLimit", s, List.of(b(CAT), n(5)), null);
        assertEquals(List.of(b(CAT), b(ANN), b(BEN)), ((MapV) lowered.fields().get(2)).keys(), "0x05… sorts before 0xa1… and 0xb1…");
        // By hand: a closer named in the phase takes half of an odd balance, the admin the rest.
        String settled = f.check("settle", ledger(rec("Closed", n(7), b(BEN)), limits, Map.of(), 0), List.of(), at(OWNER, 0, 10_000_001), OWNER);
        var pays = org.julclang.core.types.JulcList.of(new org.julclang.state.api.Payout(BEN, n(5_000_000)),
                new org.julclang.state.api.Payout(OWNER, n(5_000_001)));
        assertEquals("ok:" + result(null, pays), settled);
        assertEquals("refused", f.check("settle", s, List.of(), at(OWNER, 0, 10_000_001), OWNER), "tokens remain");
        report(f);
    }

    // ------------------------------------------------------------------ Shelf: Map of records, BigMap<Bytes, Bytes>

    static Rec item(String title, Rec kind, String... tags) {
        var ts = new ArrayList<Object>();
        for (String t : tags) ts.add(utf8(t));
        return rec("Item", utf8(title), kind, ts);
    }

    static Rec shelf(MapV items, Map<Bytes, Object> notes, Opt last) {
        return rec("State", b(OWNER), items, BigMapV.of(notes), last);
    }

    @Test
    void shelf_mapsOfRecords_bigMapOfBytes_optionsAndRanges() throws Exception {
        var f = LEGS.get("Shelf");
        f.check("init", null, List.of(), at(OWNER, 0, 0), OWNER);
        // Keys of 0 to 80 bytes, put in an order that is not canonical: set keeps the canonical order (J14).
        var ids = new ArrayList<Bytes>();
        for (int len : List.of(65, 3, 0, 64, 1, 28, 2, 80)) {
            byte[] k = new byte[len];
            for (int i = 0; i < len; i++) k[i] = (byte) (len * 7 + i);
            ids.add(Bytes.of(k));
        }
        Rec s = shelf(MapV.EMPTY, Map.of(), Opt.NONE);
        int stored = 0;
        for (Bytes id : ids) {
            Rec it = item("t" + id.bytes().length, id.bytes().length % 2 == 0 ? rec("Disc", n(id.bytes().length)) : rec("Book"), "a", "b");
            String got = f.check("put", s, List.of(id, it), null, OWNER);
            if (id.bytes().length > 64) {
                assertEquals("refused", got, "a key of " + id.bytes().length + " bytes");
            } else {
                s = f.next("put", s, List.of(id, it), null);
                stored++;
            }
        }
        var keys = ((MapV) s.fields().get(1)).keys();
        assertEquals(stored, keys.size());
        for (int i = 1; i < keys.size(); i++) {
            byte[] a = keys.get(i - 1).bytes(), c = keys.get(i).bytes();
            assertTrue(a.length < c.length || a.length == c.length && Arrays.compareUnsigned(a, c) < 0, "by hand: canonical order at " + i);
        }
        // A non-canonical item map in the state cannot be built by the language; an item with a bad Kind cannot be sent.
        assertEquals("refused", f.check("put", s, List.of(ids.get(1), item("", rec("Other"))), null, OWNER), "an empty title");
        Rec book = item("x", rec("Book"));
        for (Bytes id : List.of(ids.get(1), Bytes.of(new byte[]{9, 9}))) f.check("remove", s, List.of(id), null, OWNER);
        Map<Bytes, Object> notes = new LinkedHashMap<>();
        notes.put(utf8("k"), Bytes.of(new byte[0]));
        notes.put(utf8("q"), utf8("hello"));
        for (Map<Bytes, Object> ns : List.<Map<Bytes, Object>>of(Map.of(), notes)) {
            Rec t = shelf((MapV) s.fields().get(1), ns, Opt.some(rec("Book")));
            for (Bytes k : List.of(utf8("k"), utf8("q"), utf8("new"), Bytes.of(new byte[65]))) {
                f.check("note", t, List.of(k, utf8("!")), at(ANN, 0, 0), ANN);
                f.check("forget", t, List.of(k), null, OWNER);
                f.check("noteOf", t, List.of(k), null, null);
            }
            f.check("close", t, List.of(), at(OWNER, 0, 5_000_000), OWNER);
            f.check("minutes", t, List.of(), null, null);
            for (long k : List.of(-1L, 0L, 1L, 2L, 9L)) f.check("titles", t, List.of(n(k)), null, null);
            for (Bytes id : List.of(ids.get(1), ids.get(2), utf8("absent"))) {
                f.check("lastTag", t, List.of(id), null, null);
                f.check("position", t, List.of(id), null, null);
            }
            for (long k : List.of(-3L, 0L, 1L, 8L, 13L)) {
                f.check("squares", t, List.of(n(k)), null, null);
                f.check("evens", t, List.of(n(k)), null, null);
            }
        }
        // By hand: a note appends to the stored bytes, and absent reads as empty; an absent note cannot be forgotten.
        Rec t = shelf(MapV.EMPTY, notes, Opt.NONE);
        Map<Bytes, Object> appended = new LinkedHashMap<>(notes);
        appended.put(utf8("q"), utf8("hello!"));
        assertEquals("ok:" + result(f.java(shelf(MapV.EMPTY, appended, Opt.NONE)), null), f.check("note", t, List.of(utf8("q"), utf8("!")), at(ANN, 0, 0), ANN));
        assertEquals("refused", f.check("forget", t, List.of(utf8("zz")), null, OWNER));
        assertTrue(f.check("forget", t, List.of(utf8("k")), null, OWNER).startsWith("ok:"), "an empty note is present");
        assertEquals("refused", f.check("close", t, List.of(), at(OWNER, 0, 5_000_000), OWNER), "notes remain");
        // By hand: squares(4) = 0 + 1 + 4 + 9; evens(7) = [0, 2, 4, 6]; titles drop then take.
        assertEquals("ok:" + data(n(14)).serializeToHex(), f.check("squares", t, List.of(n(4)), null, null));
        assertEquals("ok:" + data(f.java(List.of(n(0), n(2), n(4), n(6)))).serializeToHex(), f.check("evens", t, List.of(n(7)), null, null));
        report(f);
    }

    // ------------------------------------------------------------------ StdBytes: std/bytes, std/crypto, std/bits

    static Rec stdBytes() {
        return rec("State", b(OWNER), Bytes.of(new byte[0]), n(0), n(0), false);
    }

    static final BigInteger TWO_31 = BigInteger.ONE.shiftLeft(31);

    @Test
    void stdBytes_atTheirDomainEdges() throws Exception {
        var f = LEGS.get("StdBytes");
        f.check("init", null, List.of(), at(OWNER, 0, 0), OWNER);
        Rec s = stdBytes();
        byte[] b70 = new byte[70];
        for (int i = 0; i < b70.length; i++) b70[i] = (byte) (i * 37 + 200);
        var samples = List.of(Bytes.of(new byte[0]), bs("01"), bs("ff00"), bs("0102030405"), bs("80000000000000000001"), Bytes.of(b70));
        var edges = List.of(n(-1), n(0), n(1), n(5), n(70), TWO_31.subtract(BigInteger.ONE), TWO_31, TWO_31.negate(), TWO_31.negate().add(BigInteger.ONE));
        for (Bytes x : samples) {
            f.check("num", s, List.of(x), null, null);
            f.check("hash", s, List.of(x), null, null);
            f.check("count", s, List.of(x), null, null);
            for (Bytes y : samples) {
                f.check("cat", s, List.of(x, y), null, null);
                for (boolean pad : List.of(true, false)) for (long op = 0; op < 3; op++) f.check("logic", s, List.of(pad, x, y, n(op)), null, null);
            }
            for (BigInteger k : edges) {
                for (boolean front : List.of(true, false)) f.check("cut", s, List.of(x, k, front), null, null);
                for (boolean rot : List.of(true, false)) f.check("move", s, List.of(x, k, rot), null, null);
            }
            for (BigInteger i : List.of(n(-1), n(0), n(7), n(8), n(8L * x.bytes().length - 1), n(8L * x.bytes().length), TWO_31)) {
                f.check("bit", s, List.of(x, i), null, null);
            }
        }
        // By hand.
        assertEquals(ok(f, "b", utf8("0102ff"), "n", n(0x0102ff)), f.check("num", s, List.of(bs("0102ff")), null, null));
        byte[] abc = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] h = concat(MessageDigest.getInstance("SHA-256").digest(abc), MessageDigest.getInstance("SHA3-256").digest(abc),
                Blake2bUtil.blake2bHash256(abc));
        assertEquals(ok(f, "b", Bytes.of(h)), f.check("hash", s, List.of(Bytes.of(abc)), null, null));
        assertEquals(ok(f, "b", bs("0100")), f.check("logic", s, List.of(false, bs("0102"), bs("ff00ff"), n(0)), null, null), "and, truncated");
        assertEquals(ok(f, "b", bs("ff02ff")), f.check("logic", s, List.of(true, bs("0102"), bs("ff00ff"), n(1)), null, null), "or, padded");
        assertEquals(ok(f, "b", bs("f7"), "n", n(1), "r", n(3)), f.check("count", s, List.of(bs("08")), null, null));
        assertEquals(ok(f, "flag", true), f.check("bit", s, List.of(bs("0100"), n(8)), null, null), "bit 8 is the first byte's lowest");
        assertEquals("refused", f.check("bit", s, List.of(bs("0100"), n(16)), null, null));
        assertEquals(ok(f, "b", bs("0200")), f.check("move", s, List.of(bs("0100"), n(1), false), null, null), "shift left 1");
        assertEquals(ok(f, "b", bs("8000")), f.check("move", s, List.of(bs("0001"), n(-1), true), null, null), "rotate right 1");
        assertEquals(ok(f, "b", bs("0203")), f.check("cut", s, List.of(bs("010203"), n(1), false), null, null), "drop 1");
        assertEquals(ok(f, "b", bs("010203")), f.check("cut", s, List.of(bs("010203"), TWO_31.subtract(BigInteger.ONE), true), null, null));
        assertEquals("refused", f.check("cut", s, List.of(bs("010203"), TWO_31, false), null, null), "F18: drop past 2^31 is refused");

        // Ed25519: a real signature (JDK), a wrong one, wrong lengths, and a key that is no curve point.
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        byte[] msg = "pay".getBytes(StandardCharsets.UTF_8);
        signer.update(msg);
        byte[] sig = signer.sign();
        byte[] enc = kp.getPublic().getEncoded();
        byte[] key = Arrays.copyOfRange(enc, enc.length - 32, enc.length);
        byte[] badSig = sig.clone();
        badSig[0] ^= 1;
        assertEquals(ok(f, "flag", true), f.check("sig", s, List.of(Bytes.of(key), Bytes.of(msg), Bytes.of(sig)), null, null));
        assertEquals(ok(f, "flag", false), f.check("sig", s, List.of(Bytes.of(key), utf8("other"), Bytes.of(sig)), null, null));
        // Malformed but of the right lengths: a flipped signature, a key that is no curve point, an S of 2^256 - 1. The
        // builtin answers false for each (it fails only on the lengths), and so does every leg.
        byte[] notPoint = new byte[32];
        Arrays.fill(notPoint, (byte) 0xff);
        byte[] sBig = sig.clone();
        Arrays.fill(sBig, 32, 64, (byte) 0xff);
        for (List<Object> a : List.<List<Object>>of(List.of(Bytes.of(key), Bytes.of(msg), Bytes.of(badSig)),
                List.of(Bytes.of(notPoint), Bytes.of(msg), Bytes.of(sig)), List.of(Bytes.of(key), Bytes.of(msg), Bytes.of(sBig)))) {
            assertEquals(ok(f, "flag", false), f.check("sig", s, a, null, null));
        }
        assertEquals("refused", f.check("sig", s, List.of(Bytes.of(Arrays.copyOf(key, 31)), Bytes.of(msg), Bytes.of(sig)), null, null));
        assertEquals("refused", f.check("sig", s, List.of(Bytes.of(key), Bytes.of(msg), Bytes.of(Arrays.copyOf(sig, 63))), null, null));
        report(f);
    }

    static byte[] concat(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    /** "ok:" for StdBytes' default state with the given fields replaced. */
    static String ok(FourWay f, Object... fieldValues) {
        var names = f.interp.stateFields();
        var vals = new ArrayList<Object>(stdBytes().fields());
        if (f.name.equals("StdMath")) vals = new ArrayList<>(stdMath().fields());
        for (int i = 0; i < fieldValues.length; i += 2) vals.set(names.indexOf((String) fieldValues[i]), fieldValues[i + 1]);
        return "ok:" + result(f.java(new Rec("State", vals)), null);
    }

    // ------------------------------------------------------------------ StdMath: std/math, std/list, std/time, ranges

    static Rec stdMath() {
        return rec("State", b(OWNER), n(0), n(0), Opt.NONE, List.of(), n(0), n(0));
    }

    @Test
    void stdMath_signsEdgesListsRangesAndTime() throws Exception {
        var f = LEGS.get("StdMath");
        f.check("init", null, List.of(), at(OWNER, 0, 0), OWNER);
        Rec s = stdMath();
        // div and mod floor (D6's / and % truncate): every sign pair, by hand.
        long[][] hand = {{7, 3, 2, 1}, {-7, 3, -3, 2}, {7, -3, -3, -2}, {-7, -3, 2, -1}, {0, 5, 0, 0}, {-1, 2, -1, 1}, {6, 3, 2, 0}};
        for (long[] v : hand) {
            assertEquals(ok(f, "n", n(v[2]), "r", n(v[3])), f.check("floor", s, List.of(n(v[0]), n(v[1])), null, null), v[0] + " div/mod " + v[1]);
        }
        assertEquals("refused", f.check("floor", s, List.of(n(7), n(0)), null, null));
        BigInteger big = BigInteger.TWO.pow(130).add(n(12345));
        for (BigInteger a : List.of(big, big.negate(), n(1))) for (BigInteger d : List.of(n(3), n(-3), big.negate().add(n(7)))) f.check("floor", s, List.of(a, d), null, null);
        for (BigInteger a : List.of(n(-2), n(0), n(3), big)) {
            // Up to the domain's edge where the state still fits maxDatumBytes (a datum limit the validator enforces).
            for (long e : a.equals(big) ? List.of(-1L, 0L, 1L, 5L, 1025L) : List.of(-1L, 0L, 1L, 5L, 1024L, 1025L)) {
                f.check("power", s, List.of(a, n(e)), null, null);
            }
            for (long m : List.of(-5L, 0L, 1L, 7L)) for (long e : List.of(-1L, 0L, 3L)) f.check("modPow", s, List.of(a, n(e), n(m)), null, null);
        }
        assertEquals(ok(f, "n", n(-8), "r", n(-1)), f.check("power", s, List.of(n(-2), n(3)), null, null));
        assertEquals("refused", f.check("power", s, List.of(n(2), n(1025)), null, null));
        assertEquals(ok(f, "n", n(5)), f.check("modPow", s, List.of(n(-3), n(3), n(8)), null, null), "(-27) mod 8 = 5");
        assertEquals("refused", f.check("modPow", s, List.of(n(2), n(3), n(0)), null, null));

        for (List<Object> ys : List.<List<Object>>of(List.of(), List.of(n(4)), List.of(n(1), n(2), n(3), n(2)))) {
            for (long i : List.of(-1L, 0L, 2L, 3L, 9L)) {
                for (long which = 0; which < 3; which++) f.check("pick", s, List.of(ys, n(i), n(which)), null, null);
                f.check("slices", s, List.of(ys, n(i)), null, null);
            }
        }
        assertEquals(ok(f, "o", Opt.some(n(1))), f.check("pick", s, List.of(List.of(n(1), n(2), n(3), n(2)), n(2), n(2)), null, null), "indexOf 2");
        assertEquals(ok(f, "xs", List.of(n(1), n(4), n(3), n(2))), f.check("slices", s, List.of(List.of(n(1), n(2), n(3), n(4)), n(1)), null, null));
        assertEquals("refused", f.check("slices", s, List.of(List.of(n(1)), n(-1)), null, null));
        assertEquals("refused", f.check("pick", s, List.of(List.of(n(1)), n(-1), n(1)), null, null));
        for (long a : List.of(-4L, 0L, 3L, 9L)) for (long b2 : List.of(-4L, 0L, 3L, 9L)) f.check("ranged", s, List.of(n(a), n(b2)), null, null);
        // By hand: range(3, 9) is 3..8: multiples of three 3 and 6, doubled; the sum 33; 7 is in it.
        assertEquals(ok(f, "xs", List.of(n(6), n(12)), "n", n(33), "o", Opt.some(n(7))), f.check("ranged", s, List.of(n(3), n(9)), null, null));
        assertEquals(ok(f, "xs", List.of(), "n", n(0)), f.check("ranged", s, List.of(n(9), n(3)), null, null), "a descending range is empty");
        // std/time: elapsed since t, clamped at 0, from each end of the window.
        for (BigInteger t : List.of(T0.subtract(n(5_000)), T0, T0.add(n(60_000)), T0.add(WINDOW).add(n(1)))) f.check("stamp", s, List.of(t), at(OWNER, 0, 0), OWNER);
        assertEquals(ok(f, "since", n(5_000), "upTo", n(125_000)), f.check("stamp", s, List.of(T0.subtract(n(5_000))), at(OWNER, 0, 0), OWNER));
        assertEquals(ok(f, "since", n(0), "upTo", n(60_000)), f.check("stamp", s, List.of(T0.add(n(60_000))), at(OWNER, 0, 0), OWNER));
        report(f);
    }
}
