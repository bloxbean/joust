package org.julclang.state.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.api.RequireException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D12 (r12, review r1 F4): on the JVM, a .jsc 2 contract's refusal names its .jsc sites from the message map
 * the build puts beside the contract's classes; a contract without a map is unchanged. The matching rule is the one
 * the dsl and the JS runtime use (the shared vectors, review r1 F5).
 */
class JscMessagesTest {

    @Test
    void aRefusalNamesItsSites_fromTheMapBesideTheContract() {
        var e = JscMessages.explained(JscMessagesTest.class, "Pay", new RequireException("#pay:spend1.10"));
        assertEquals("#pay:spend1.10 [Pay.jsc:17:5 (a payout in split is negative)]", e.getMessage());
        assertInstanceOf(RequireException.class, e);
        assertEquals("split: not negative [Pay.jsc:9:41 (a require in split)]",
                JscMessages.explained(JscMessagesTest.class, "Pay", new RequireException("split: not negative")).getMessage());
        var unknown = new RequireException("#pay:spend1.11");
        assertSame(unknown, JscMessages.explained(JscMessagesTest.class, "Pay", unknown));
        var noMap = new RequireException("#pay:spend1.1");
        assertSame(noMap, JscMessages.explained(JscMessagesTest.class, "JavaContract", noMap), "a Java contract has no map");
        assertSame(noMap, JscMessages.explained(null, "Described", noMap), "a described contract has no class");
    }

    @Test
    void theSharedMatchingVectors() throws Exception {
        JsonNode v = new ObjectMapper().readTree(Path.of("../julc-state-dsl/src/test/resources/jsc2-message-matching.json").toFile());
        var messages = new ArrayList<String>();
        v.get("messages").forEach(m -> messages.add(m.asText()));
        for (JsonNode x : v.get("vectors")) {
            String want = x.get("expected").isNull() ? null : x.get("expected").asText();
            assertEquals(want, JscMessages.matching(x.get("trace").asText(), messages), x.get("trace").asText());
        }
        assertTrue(List.copyOf(messages).size() > 5);
    }
}
