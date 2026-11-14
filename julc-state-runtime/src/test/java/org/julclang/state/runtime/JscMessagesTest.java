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

    /** Review S4 on #76: a map is taken from the contract's own code source, else only when the class path has one copy. */
    @Test
    void aMapIsNeverAGuess_twoCopiesOnTheClassPathGiveNone() throws Exception {
        Path a = java.nio.file.Files.createTempDirectory("jscmap-a"), b = java.nio.file.Files.createTempDirectory("jscmap-b");
        for (Path d : List.of(a, b)) {
            java.nio.file.Files.createDirectories(d.resolve("t"));
            java.nio.file.Files.writeString(d.resolve("t/Pay.jscmap.json"), "{\"source\": \"" + d.getFileName() + "\", \"messages\": {}}");
        }
        try (var one = new java.net.URLClassLoader(new java.net.URL[]{a.toUri().toURL()}, null);
             var two = new java.net.URLClassLoader(new java.net.URL[]{a.toUri().toURL(), b.toUri().toURL()}, null)) {
            assertTrue(JscMessages.only(java.util.Collections.list(one.getResources("t/Pay.jscmap.json"))).contains(a.getFileName().toString()));
            assertNull(JscMessages.only(java.util.Collections.list(two.getResources("t/Pay.jscmap.json"))), "two copies: neither");
            assertNull(JscMessages.only(java.util.Collections.list(one.getResources("t/None.jscmap.json"))));
        }
        // The contract's own code source is read first: this test class's directory holds no map, the class path one.
        assertNull(ValidatorScripts.besideClass(JscMessagesTest.class, "org/julclang/state/runtime/Pay.jscmap.json"));
        assertNotNull(JscMessages.map(JscMessagesTest.class, "Pay"));
    }

    /**
     * Issue #74 (Codex C6): the cache is keyed by class, not by class name. Two class loaders that each load a contract
     * class of the same name, each with its own map beside it, get their own map (the second used to get the first's).
     */
    @Test
    void twoLoadersOfTheSameName_eachGetTheirOwnMap() throws Exception {
        String cls = "org/julclang/state/runtime/fixture/MapAnchor.class";
        byte[] bytes;
        try (var in = JscMessagesTest.class.getClassLoader().getResourceAsStream(cls)) {
            bytes = in.readAllBytes();
        }
        var loaded = new ArrayList<Class<?>>();
        var loaders = new ArrayList<java.net.URLClassLoader>();
        try {
            for (String source : List.of("First.jsc", "Second.jsc")) {
                Path d = java.nio.file.Files.createTempDirectory("jscmap-loader");
                java.nio.file.Files.createDirectories(d.resolve(cls).getParent());
                java.nio.file.Files.write(d.resolve(cls), bytes);
                java.nio.file.Files.writeString(d.resolve("org/julclang/state/runtime/fixture/Pay.jscmap.json"), "{\"source\": \"" + source
                        + "\", \"messages\": {\"#pay:spend0.1\": [{\"line\": 3, \"col\": 5, \"kind\": \"pay\", \"in\": \"sweep\"}]}}");
                var loader = new java.net.URLClassLoader(new java.net.URL[]{d.toUri().toURL()}, null);
                loaders.add(loader);
                loaded.add(loader.loadClass("org.julclang.state.runtime.fixture.MapAnchor"));
            }
            assertEquals(loaded.get(0).getName(), loaded.get(1).getName());
            assertNotSame(loaded.get(0), loaded.get(1));
            assertEquals(List.of("First.jsc:3:5 (a payout in sweep is negative)"), JscMessages.sites(loaded.get(0), "Pay", "#pay:spend0.1"));
            assertEquals(List.of("Second.jsc:3:5 (a payout in sweep is negative)"), JscMessages.sites(loaded.get(1), "Pay", "#pay:spend0.1"));
            assertEquals(List.of("First.jsc:3:5 (a payout in sweep is negative)"), JscMessages.sites(loaded.get(0), "Pay", "#pay:spend0.1"));
        } finally {
            for (var l : loaders) l.close();
        }
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
