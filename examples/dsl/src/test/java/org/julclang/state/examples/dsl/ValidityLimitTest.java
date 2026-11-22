package org.julclang.state.examples.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.runtime.described.DescribedContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A described client (the npm host's engine, pages) runs a Ctx-reading call within the contract's own validity limit,
 * as the generated client does: the description carries a limit other than the default, and DescribedContract reads it
 * (#154 Codex review). Keys and Mix set 600,000 ms; Jar keeps the default hour.
 */
class ValidityLimitTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static DescribedContract described(String contract) throws Exception {
        JsonNode description, validator;
        try (var in = ValidityLimitTest.class.getResourceAsStream("/META-INF/joust/org.julclang.state.examples.dsl." + contract + ".json")) {
            description = JSON.readTree(in);
        }
        try (var in = ValidityLimitTest.class.getResourceAsStream("/META-INF/plutus/" + contract + "Validator.plutus.json")) {
            validator = JSON.readTree(in);
        }
        return new DescribedContract(description, validator.get("cborHex").asText(), (m, t, s, a, c) -> {
            throw new AssertionError("no transition runs here");
        });
    }

    @Test
    void aDescribedContractKeepsTheContractsValidityLimit() throws Exception {
        assertEquals(600_000L, KeysClient.SPEC.maxValidityMs());
        assertEquals(KeysClient.SPEC.maxValidityMs(), described("Keys").spec().maxValidityMs());
        assertEquals(MixClient.SPEC.maxValidityMs(), described("Mix").spec().maxValidityMs());
    }

    @Test
    void theDefaultLimitIsLeftOutOfTheDescription() throws Exception {
        assertEquals(3_600_000L, JarClient.SPEC.maxValidityMs());
        assertEquals(3_600_000L, described("Jar").spec().maxValidityMs());
        try (var in = getClass().getResourceAsStream("/META-INF/joust/org.julclang.state.examples.dsl.Jar.json")) {
            assertFalse(JSON.readTree(in).path("x-joust").has("maxValidityMs"));
        }
    }
}
