package org.julclang.state.examples.dsl;

import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.types.JulcList;
import org.julclang.state.testkit.Descriptions;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M0, "Option as Optional end to end", the JS leg: {@code js/state-client/test/jsc2-option.test.js} decodes
 * and encodes Pledges' {@code Option<Key>} state field and {@code setBacker} redeemer through the page codec, from the
 * generated description, and must produce the JVM's CBOR. This test keeps both fixtures current: the description copy
 * and the JVM's encodings ({@code JOUST_WRITE_FIXTURES=1} rewrites them).
 */
class Dsl2JsFixturesTest {

    static final Path FIXTURES = Path.of("../../js/state-client/test/fixtures");
    static final String RESOURCE = "META-INF/joust/org.julclang.state.examples.dsl.Pledges.json";

    static String hex(Object o) {
        return PlutusDataAdapter.convert(o).serializeToHex();
    }

    static Pledges.State state(Optional<byte[]> last) {
        var shares = JulcList.of(new Pledges.Share(StateHarness.fill(28, 0xA1), BigInteger.valueOf(6_000)),
                new Pledges.Share(StateHarness.fill(28, 0xB1), BigInteger.valueOf(4_000)));
        return new Pledges.State(StateHarness.fill(28, 0x0A), BigInteger.valueOf(10_000_000), StateHarness.T0, BigInteger.ZERO, shares,
                new byte[]{1, 2}, last);
    }

    static void check(Path file, String want) throws Exception {
        if (System.getenv("JOUST_WRITE_FIXTURES") != null) Files.writeString(file, want);
        assertTrue(Files.exists(file), file + " is missing");
        assertEquals(want, Files.readString(file), file + " is stale: rerun with JOUST_WRITE_FIXTURES=1");
    }

    @Test
    void theJavaScriptFixturesAreCurrent() throws Exception {
        String description;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            description = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        check(FIXTURES.resolve("pledges.description.json"), description);
        byte[] ben = StateHarness.fill(28, 0xB1);
        String vectors = "{\n"
                + "  \"deadline\": \"" + StateHarness.T0 + "\",\n"
                + "  \"stateSome\": \"" + hex(state(Optional.of(ben))) + "\",\n"
                + "  \"stateNone\": \"" + hex(state(Optional.empty())) + "\",\n"
                + "  \"setBackerSome\": \"" + hex(new PledgesValidator.SetBacker(Optional.of(ben))) + "\",\n"
                + "  \"setBackerNone\": \"" + hex(new PledgesValidator.SetBacker(Optional.empty())) + "\"\n"
                + "}\n";
        check(FIXTURES.resolve("pledges.vectors.json"), vectors);
    }

    @Test
    void jsc2DescriptionsMatchTheirValidators() {
        Descriptions.assertMatches(Counter2.class, Counter2Validator.class);
        Descriptions.assertMatches(Pledges.class, PledgesValidator.class);
        Descriptions.assertMatches(Vectors.class, VectorsValidator.class);
    }
}
