package org.julclang.state.runtime;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.CborDecodingException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plutus data from CBOR bytes, without recursion: JOUST's one decoder of datums, redeemers and other Plutus data given
 * as CBOR. It returns what JuLC's {@code PlutusDataCborDecoder} returns, and rejects what it rejects (Plutus
 * {@code decodeData}: exactly one item; byte strings in chunks of at most 64 bytes; bignums only in the heads
 * {@code c2}/{@code c3}; constructors 121-127, 1280-1400 and 102 {@code [alternative, fields]}; no text strings, simple
 * values, floats or other tags), but that decoder recurses once per level, so deeply nested data (some 16,000 levels
 * fit in a transaction) overflows the stack: on the JVM a {@link StackOverflowError}, in a GraalVM Web Image (the npm
 * engines) a trap that cannot be caught.
 * <p>
 * Here the bytes are read through cardano-client-lib's {@link CborSpan}, whose walker keeps containers on an explicit
 * stack: {@link CborSpan#of(byte[])} first checks, in one pass, that they are exactly one well-formed item (RFC 8949
 * section 5.3: truncation, declared sizes against the remaining input, BREAK placement, string chunks), and every
 * integer, bignum and byte string is then read as a span ({@link CborSpan#asBigInteger()},
 * {@link CborSpan#byteString()}). The items are converted in one pass in encoded order, with an explicit stack of open
 * containers, reading only the heads of arrays, maps and tags from the bytes the walker has checked. Any nesting depth
 * therefore decodes on any thread, in time linear in the size of the bytes.
 * <p>
 * The data comes from the original bytes, not from a decoded tree: cardano-client-lib's cbor-java trees
 * ({@code CborSerializationUtil.deserialize}) and its Plutus data model ({@code PlutusData.deserialize}, iterative too)
 * keep one entry per map key, where a Plutus map is a list of pairs that may repeat keys, and no longer show the
 * chunks of a byte string or the head of a tag; the model also reads text strings as bytes. Reading spans keeps every
 * pair, chunk and head as encoded.
 * <p>
 * Nothing here re-encodes: a caller that hashes data, compares it byte for byte or must reproduce its encoding uses the
 * bytes it decoded (a value's own encoding is {@code PlutusDataCborEncoder}'s, which differs from the original wherever
 * the writer chose another encoding). Callers that feed the value to the VM apply their own depth limits, as before.
 */
public final class PlutusBytes {
    private static final int MAX_BYTES = 64;
    private static final int BIGNUM_POSITIVE = 0xc2;
    private static final int BIGNUM_NEGATIVE = 0xc3;
    private static final int BREAK = 0xff;
    private static final int MAJOR_UNSIGNED = 0;
    private static final int MAJOR_BYTES = 2;
    private static final int MAJOR_ARRAY = 4;
    private static final int MAJOR_MAP = 5;
    private static final int MAJOR_TAG = 6;
    private static final int INDEFINITE = -1;

    private PlutusBytes() {}

    /**
     * Decodes CBOR that holds exactly one Plutus data item (trailing bytes are rejected, as the ledger rejects them in
     * an inline datum), keeping map pairs in order, repeated keys included.
     *
     * @param cbor the CBOR bytes; they are not copied and must not change while this runs
     * @return the data
     * @throws CborDecodingException if the bytes are not one Plutus data item
     */
    public static PlutusData decode(byte[] cbor) {
        Objects.requireNonNull(cbor, "cbor");
        try {
            CborSpan.of(cbor);
        } catch (RuntimeException e) {
            throw new CborDecodingException("CBOR decoding failed: " + e.getMessage(), e);
        }
        return new Reader(cbor).read();
    }

    /**
     * {@link #decode(byte[])} of hex.
     *
     * @throws IllegalArgumentException if the text is not hex (as {@link java.util.HexFormat#parseHex} throws)
     */
    public static PlutusData decodeHex(String hex) {
        return decode(java.util.HexFormat.of().parseHex(hex));
    }

    /** One open list, map or constructor; frames are chained through {@code parent}. */
    private static final class Frame {
        final Frame parent;
        final boolean map;
        final BigInteger tag;            // a constructor's alternative; null for a list or map
        final boolean closesOuter;       // a 102 constructor in an indefinite outer array: its BREAK follows the fields
        int remaining;                   // items still to read, INDEFINITE until BREAK
        final List<PlutusData> out;

        Frame(Frame parent, boolean map, BigInteger tag, boolean closesOuter, int remaining) {
            this.parent = parent;
            this.map = map;
            this.tag = tag;
            this.closesOuter = closesOuter;
            this.remaining = remaining;
            this.out = new ArrayList<>(remaining > 0 ? Math.min(remaining, 1024) : 4);
        }

        PlutusData result() {
            if (map) {
                var pairs = new ArrayList<PlutusData.Pair>(out.size() / 2);
                for (int i = 0; i < out.size(); i += 2) pairs.add(new PlutusData.Pair(out.get(i), out.get(i + 1)));
                return new PlutusData.MapData(pairs);
            }
            return tag == null ? new PlutusData.ListData(out) : PlutusData.ConstrData.fromUnsignedTag(tag, out);
        }
    }

    /** Reads bytes that {@link CborSpan#of(byte[])} has walked, so every head and item in them is well-formed. */
    private static final class Reader {
        private final byte[] buffer;
        private int pos;
        // The head just read.
        private int major;
        private long argument;           // unsigned 64-bit
        private boolean indefinite;

        Reader(byte[] buffer) {
            this.buffer = buffer;
        }

        PlutusData read() {
            Frame top = null;
            while (true) {
                PlutusData done = null;
                if (top != null && (top.remaining == 0 || (top.remaining == INDEFINITE && at() == BREAK))) {
                    if (top.remaining == INDEFINITE) pos++;
                    if (top.closesOuter) {
                        // A 102 constructor's outer array holds exactly [alternative, fields].
                        if (at() != BREAK) throw reject("Constr general encoding expects [tag, fields], got more elements");
                        pos++;
                    }
                    done = top.result();
                    top = top.parent;
                } else {
                    if (top != null && top.remaining > 0) top.remaining--;
                    Object item = item(top);
                    if (item instanceof Frame opened) {
                        top = opened;
                        continue;
                    }
                    done = (PlutusData) item;
                }
                if (top == null) {
                    if (pos != buffer.length) throw reject("Trailing bytes after PlutusData");   // checked by the walk
                    return done;
                }
                top.out.add(done);
            }
        }

        /** The item at {@code pos}: a leaf, read, or a container, opened as a frame on {@code parent}. */
        private Object item(Frame parent) {
            int start = pos;
            int initial = at();
            head();
            switch (major) {
                case 0, 1:
                    return new PlutusData.IntData(leaf(start).asBigInteger());
                case MAJOR_BYTES:
                    return new PlutusData.BytesData(boundedBytes(leaf(start)));
                case MAJOR_ARRAY:
                    return new Frame(parent, false, null, false, count(1));
                case MAJOR_MAP:
                    return new Frame(parent, true, null, false, count(2));
                case MAJOR_TAG:
                    return tagged(parent, initial, argument);
                case 3:
                    throw reject("Text strings are not PlutusData");
                default:
                    throw reject("Unsupported CBOR major type for PlutusData: " + major);
            }
        }

        private Object tagged(Frame parent, int initial, long tag) {
            int content = pos;
            if (at() >>> 5 == MAJOR_TAG) throw reject("Nested CBOR tags are not PlutusData");
            if (initial == BIGNUM_POSITIVE || initial == BIGNUM_NEGATIVE) {
                // cborg reads tags 2 and 3 as bignums only in the heads c2/c3.
                head();
                if (major != MAJOR_BYTES) throw reject("Tag " + tag + " must wrap a byte string");
                BigInteger n = new BigInteger(1, boundedBytes(leaf(content)));
                return new PlutusData.IntData(initial == BIGNUM_POSITIVE ? n : n.add(BigInteger.ONE).negate());
            }
            if (Long.compareUnsigned(tag, 3) <= 0) throw reject("Unrecognized tag " + tag);
            head();
            if (major != MAJOR_ARRAY) throw reject("Tag " + Long.toUnsignedString(tag) + " must wrap an array");
            if (tag >= 121 && tag <= 127) return new Frame(parent, false, BigInteger.valueOf(tag - 121), false, count(1));
            if (tag >= 1280 && tag <= 1400) {
                return new Frame(parent, false, BigInteger.valueOf(tag - 1280 + 7), false, count(1));
            }
            if (tag != 102) throw reject("Unrecognized CBOR tag for PlutusData: " + Long.toUnsignedString(tag));
            // 102 [alternative, fields], whose outer array may be indefinite (plutus-core decodeConstrExtended).
            boolean outerIndefinite = indefinite;
            if (!outerIndefinite && argument != 2) {
                throw reject("Constr general encoding expects [tag, fields], got " + Long.toUnsignedString(argument)
                        + " elements");
            }
            if (at() == BREAK) throw reject("Constr general encoding expects [tag, fields], got 0 elements");
            int alternative = pos;
            head();
            if (major != MAJOR_UNSIGNED) throw reject("Expected unsigned Word64 for Constr tag");
            BigInteger value = leaf(alternative).asBigInteger();
            if (at() == BREAK) throw reject("Constr general encoding expects [tag, fields], got 1 elements");
            head();
            if (major != MAJOR_ARRAY) throw reject("Expected array for Constr fields in general encoding");
            return new Frame(parent, false, value, outerIndefinite, count(1));
        }

        private int at() {
            return buffer[pos] & 0xff;
        }

        /** Reads the head at {@code pos} (major type, argument, indefinite) and moves past it. */
        private void head() {
            int initial = buffer[pos++] & 0xff;
            major = initial >>> 5;
            int info = initial & 0x1f;
            indefinite = info == 31;
            if (info < 24 || indefinite) {
                argument = info;
                return;
            }
            int size = 1 << (info - 24);   // 24..27: the walk rejected 28..30
            long value = 0;
            for (int i = 0; i < size; i++) value = (value << 8) | (buffer[pos++] & 0xff);
            argument = value;
        }

        /** The item count of the container whose head was just read: its items, or INDEFINITE. */
        private int count(int perEntry) {
            if (indefinite) return INDEFINITE;
            // The walk checked the count against the input, so it fits an int.
            return Math.toIntExact(argument * perEntry);
        }

        /** The leaf item that starts at {@code start} (its head just read), as a span; moves past it. */
        private CborSpan leaf(int start) {
            CborSpan span = CborSpan.at(buffer, start);
            pos = start + span.length();
            return span;
        }
    }

    /**
     * Plutus {@code decodeBoundedBytes}: a definite byte string is at most 64 bytes, and an indefinite one is a
     * sequence of such definite chunks (the walk checked that its chunks are definite byte strings).
     */
    private static byte[] boundedBytes(CborSpan bytes) {
        if (!bytes.isIndefinite()) {
            if (bytes.length() - bytes.headerLength() > MAX_BYTES) throw reject("ByteString exceeds 64 bytes");
            return bytes.byteString();
        }
        byte[] buffer = bytes.buffer();
        int pos = bytes.offset() + bytes.headerLength();
        while ((buffer[pos] & 0xff) != BREAK) {
            CborSpan chunk = CborSpan.at(buffer, pos);
            if (chunk.length() - chunk.headerLength() > MAX_BYTES) throw reject("ByteString exceeds 64 bytes");
            pos += chunk.length();
        }
        return bytes.byteString();
    }

    private static CborDecodingException reject(String why) {
        return new CborDecodingException(why);
    }
}
