package org.julclang.state.serve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.core.PlutusData;
import org.julclang.state.runtime.ContractSpec;
import org.julclang.state.runtime.StateRuntime;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** #145 Codex review 3, 4: the dev host's codec reads the wire strictly; the HTTP service's keeps its behaviour. */
class JsonValuesTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long SAFE = (1L << 53) - 1;

    static JsonNode json(String text) throws Exception { return JSON.readTree(text); }

    @Test void strictIntegersAreSafeJsonNumbersOrDecimalStrings() throws Exception {
        for (long ok : new long[] {SAFE, -SAFE, 0}) {
            assertEquals(BigInteger.valueOf(ok), JsonValues.TYPED.decode(json(Long.toString(ok)), BigInteger.class, "n"));
            assertEquals(ok, JsonValues.TYPED.decode(json(Long.toString(ok)), long.class, "n"));
        }
        for (String bad : new String[] {"9007199254740992", "-9007199254740992", "-9223372036854775808", "9223372036854775807"}) {
            assertThrows(JsonValues.BadValue.class, () -> JsonValues.TYPED.decode(json(bad), BigInteger.class, "n"), bad);
            // The same values as decimal strings are exact.
            assertEquals(new BigInteger(bad), JsonValues.TYPED.decode(json("\"" + bad + "\""), BigInteger.class, "n"));
        }
        // Out of a Java type's range: a bad value, not an ArithmeticException.
        assertThrows(JsonValues.BadValue.class, () -> JsonValues.TYPED.decode(json("\"9223372036854775808\""), long.class, "n"));
        assertThrows(JsonValues.BadValue.class, () -> JsonValues.TYPED.decode(json("2147483648"), int.class, "n"));
    }

    @Test void theHttpCodecKeepsItsBounds() throws Exception {
        assertEquals(BigInteger.ONE.shiftLeft(53), JsonValues.BAD_VALUES.decode(json("9007199254740992"), BigInteger.class, "n"));
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE), JsonValues.BAD_VALUES.decode(json("-9223372036854775808"), BigInteger.class, "n"));
        assertThrows(JsonValues.BadValue.class, () -> JsonValues.BAD_VALUES.decode(json("9007199254740993"), BigInteger.class, "n"));
    }

    @Test void strictStringsAreWellFormedUtf8AndRoundTrip() throws Exception {
        // "é" and "€": valid multibyte sequences come back as the same bytes.
        for (String hex : new String[] {"c3a9", "e282ac", "61", ""}) {
            Object s = JsonValues.TYPED.decode(json("\"" + hex + "\""), String.class, "s");
            assertEquals(hex, JsonValues.toJson(s).textValue());
        }
        // A lone continuation byte, a truncated sequence, an overlong encoding, a surrogate.
        for (String hex : new String[] {"ff", "80", "c3", "c0af", "eda080"}) {
            assertThrows(JsonValues.BadValue.class, () -> JsonValues.TYPED.decode(json("\"" + hex + "\""), String.class, "s"), hex);
        }
        // The HTTP codec replaces, as it always has.
        assertEquals("efbfbd", JsonValues.toJson(JsonValues.BAD_VALUES.decode(json("\"ff\""), String.class, "s")).textValue());
    }

    /** A hand-written client (description in test resources) with views over byte, short and Plutus data. */
    public static final class SmallClient {
        public static final ContractSpec<Object> SPEC = new ContractSpec<>("Small", Object.class, Object.class, 0, 0, 0,
                false, null, List.of());

        public static SmallClient at(StateRuntime runtime, String instance) { return new SmallClient(); }

        public short nextShort(short n) { return (short) (n + 1); }

        public byte nextByte(byte n) { return (byte) (n + 1); }

        public long data(PlutusData d) { return 0; }
    }

    static final String INSTANCE = "00".repeat(32);

    static JsonNode view(JsonValues codec, String view, String args) throws Exception {
        return ServedContract.of(SmallClient.class, codec).viewJson(null, INSTANCE, view, json(args));
    }

    /** #145 Codex final pass: the description shows byte and short as integers; the codec reads and shows them. */
    @Test void byteAndShortViewsTakeAndShowIntegers() throws Exception {
        assertEquals("2", view(JsonValues.TYPED, "nextShort", "{\"n\": 1}").textValue());
        assertEquals("-32767", view(JsonValues.TYPED, "nextShort", "{\"n\": \"-32768\"}").textValue());
        assertEquals("127", view(JsonValues.TYPED, "nextByte", "{\"n\": 126}").textValue());
        assertEquals("-127", view(JsonValues.TYPED, "nextByte", "{\"n\": \"-128\"}").textValue());
        for (String bad : new String[] {"32768", "-32769"}) {
            assertThrows(JsonValues.BadValue.class, () -> view(JsonValues.TYPED, "nextShort", "{\"n\": " + bad + "}"), bad);
        }
        for (String bad : new String[] {"128", "-129", "1.5"}) {
            assertThrows(JsonValues.BadValue.class, () -> view(JsonValues.TYPED, "nextByte", "{\"n\": " + bad + "}"), bad);
        }
    }

    /**
     * #145 Codex final pass: Plutus data too deep for the decoder's stack reaches the dev host's per-call handler as
     * the StackOverflowError (internal/tooDeep, ADR-0022 D9); the HTTP codec keeps calling it a bad value.
     */
    @Test void tooDeepPlutusDataOverflowsOnlyInTheTypedCodec() throws Exception {
        String deep = "{\"d\": \"" + "81".repeat(16000) + "80\"}";
        assertInstanceOf(StackOverflowError.class, onASmallStack(() -> view(JsonValues.TYPED, "data", deep)));
        assertInstanceOf(JsonValues.BadValue.class, onASmallStack(() -> view(JsonValues.BAD_VALUES, "data", deep)));
        assertEquals("0", view(JsonValues.TYPED, "data", "{\"d\": \"8180\"}").textValue());
    }

    /** What {@code call} throws on a thread with a small stack (so the decoder's recursion overflows for certain). */
    static Throwable onASmallStack(java.util.concurrent.Callable<?> call) throws Exception {
        var thrown = new AtomicReference<Throwable>();
        Thread t = new Thread(null, () -> {
            try {
                call.call();
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "small-stack", 128 * 1024);
        t.start();
        t.join();
        return thrown.get();
    }
}
