package org.julclang.state.serve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

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
}
